package io.mazewall.platform.seccomp.daemon

import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.ffi.memory.writeByte
import io.mazewall.ffi.networking.SeccompConnection
import io.mazewall.ffi.networking.SeccompConnectionEffect
import io.mazewall.ffi.networking.SeccompConnectionEvent
import io.mazewall.ffi.networking.SeccompConnectionMachine
import io.mazewall.ffi.networking.SeccompConnectionOutcome
import io.mazewall.platform.daemon.UnixListenDaemonEffect
import io.mazewall.platform.daemon.UnixListenDaemonEvent
import io.mazewall.platform.daemon.UnixListenDaemonMachine
import io.mazewall.platform.daemon.UnixListenDaemonOutcome
import io.mazewall.platform.daemon.UnixListenDaemonState
import io.mazewall.platform.daemon.UnixListenDaemonTransition
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.Deadline
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.RawSyscallOperations
import io.mazewall.platform.native.getFdOrThrow
import io.mazewall.platform.native.recover
import io.mazewall.platform.socket.NativeCloseFailure
import io.mazewall.platform.socket.RealSocketManager
import io.mazewall.platform.socket.SocketManager
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Private control traffic shares the accepted socket owner, never its listener. */
internal sealed interface PreludeOutcome {
    data object LegacyListener : PreludeOutcome

    data object Handled : PreludeOutcome

    class LiveListener(
        val admission: LiveListenerAdmission,
    ) : PreludeOutcome
}

/** Connection-owned one-shot admission; its producer retains the original setup. */
internal interface LiveListenerAdmission : AutoCloseable

internal class SeccompConnectionHooks(
    val connectionAcceptor: (
        (FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>) -> FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>
    )? = null,
    val beforeListener: ((FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>, Deadline) -> PreludeOutcome)? = null,
    val liveListenerFactory: (
        (
            LiveListenerAdmission,
            FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
            FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
        ) -> SeccompNotifHandler
    )? = null,
    val afterConnectionClosed: ((FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>, SeccompCleanupOutcome) -> Unit)? = null,
)

/** Result of the bounded daemon handshake acknowledgement write. */
internal sealed interface HandshakeWriteOutcome {
    data object Sent : HandshakeWriteOutcome

    /** The socket was not writable; the reactor must poll before trying again. */
    data object RetryAfterPoll : HandshakeWriteOutcome

    data object TimedOut : HandshakeWriteOutcome

    data class Failed(
        val errno: Int,
    ) : HandshakeWriteOutcome
}

/** Deadlines follow connection objects, never recyclable integer descriptor values. */
internal class HandshakeDeadlineRegistry {
    private val deadlines = Collections.synchronizedMap(IdentityHashMap<SeccompConnection, Deadline>())

    fun getOrCreate(
        connection: SeccompConnection,
        create: () -> Deadline,
    ): Deadline = synchronized(deadlines) { deadlines[connection] ?: create().also { deadlines[connection] = it } }

    fun remove(connection: SeccompConnection): Deadline? = deadlines.remove(connection)

    val size: Int get() = deadlines.size
}

/**
 * Unified daemon engine for handling seccomp user notification UNIX server, client connections,
 * handshake protocol, and session reactors.
 */
internal class SeccompDaemonEngine(
    private val socketPath: String,
    private val readySentinel: String = "MAZEWALL_DAEMON_READY",
    private val notifHandlerFactory: (
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
    ) -> SeccompNotifHandler,
    private val handshakeAckByte: Byte = PROTOCOL_ACK_BYTE,
    private val maxConnections: Int = 200,
    private val engine: NativeEngine = LinuxNative,
    private val socketManager: SocketManager = RealSocketManager,
    private val raw: RawSyscallOperations = engine.raw,
    private val handshakeWriter: (
        FileDescriptor<*, FdState.Open, FdOwnership.Owned>,
        ManagedSegment,
        Long,
    ) -> LinuxNative.SyscallResult<Long, *> = engine.memory::write,
    private val hooks: SeccompConnectionHooks = SeccompConnectionHooks(),
) {
    private val connectionExecutor: ExecutorService = Executors.newFixedThreadPool(maxConnections) { r ->
        Thread(r).apply {
            isDaemon = true
            name = "seccomp-conn"
        }
    }

    private class AdmissionRecord {
        var socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>? = null
        var current: SeccompConnection? = null
        var reactor: Thread? = null
        var session: SeccompSessionHandler? = null
        var liveAdmission: LiveListenerAdmission? = null
        var retirement: SessionRetirement = SessionRetirement.Complete
        var socketUnknown = false
        var advancing = false
        var taskStarted = false
        var queuedRetirement = false
    }

    private val admissionGate = Any()
    private val admissions = mutableListOf<AdmissionRecord>()
    private var admissionClosed = false

    private fun reserveAdmission(): AdmissionRecord? =
        synchronized(admissionGate) {
        if (admissionClosed || admissions.size >= maxConnections) return null
        AdmissionRecord().also { admissions.add(it) }
    }

    private fun bindAdmission(
        record: AdmissionRecord,
        socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        accepted: Boolean,
    ): Boolean =
        synchronized(admissionGate) {
        check(admissions.any { it === record } && record.socket == null)
        if (!accepted && admissionClosed) return false
        if (!accepted && admissions.any { it !== record && it.socket?.value == socket.value }) return false
        record.socket = socket
        true
    }

    private fun releaseAdmission(record: AdmissionRecord) {
        synchronized(admissionGate) { admissions.removeAll { it === record } }
    }

    @JvmField
    public val clientSockets = CopyOnWriteArrayList<FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>>()

    private val activeListeners = CopyOnWriteArrayList<FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>>()
    private val incompleteConnections = CopyOnWriteArrayList<SeccompConnection>()
    private val failedServerCloses = CopyOnWriteArrayList<FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>>()
    private val cleanupFailures = CopyOnWriteArrayList<Exception>()
    private val handshakeDeadlines = HandshakeDeadlineRegistry()

    /** Historical failures stay visible even if a later shutdown retry releases all descriptors. */
    @io.mazewall.platform.lifecycle.MazewallInternal
    public val cleanupOutcome: SeccompCleanupOutcome
        get() = if (cleanupFailures.isEmpty() && incompleteConnections.isEmpty() && failedServerCloses.isEmpty() && synchronized(admissionGate) { !admissionClosed || admissions.isEmpty() }) {
            SeccompCleanupOutcome.Complete
        } else {
            SeccompCleanupOutcome.Incomplete(
                cleanupFailures.toList(),
                (incompleteConnections.flatMap { it.retryableOwnership() } + failedServerCloses).distinct(),
            )
        }
    private val stateRef = AtomicReference<UnixListenDaemonState>(UnixListenDaemonState.Uninitialized)
    public var state: UnixListenDaemonState
        get() = stateRef.get()
        private set(value) = stateRef.set(value)

    public companion object {
        private const val POLL_TIMEOUT_MS = 1000
        private const val POLLFD_STRUCT_SIZE = 8L
        private const val ACK_BUF_SIZE = 1L
        private const val CONNECTION_WORKER_DRAIN_TIMEOUT_MS = 5_000L
        private const val PROTOCOL_ACK_BYTE = 0xAC.toByte()
        private const val HANDSHAKE_ACK_TIMEOUT_MS = 30_000L
        private const val PRELUDE_TIMEOUT_MS = 5_000L
    }

    public fun run() {
        val serverFd = socketManager.createUnixServer(socketPath)
        applyEvent(UnixListenDaemonEvent.Bound(serverFd, socketPath))
        applyEvent(UnixListenDaemonEvent.ReadyAnnounced)

        try {
            NativeArena.ofConfined().use { arena ->
                val pollFd = PollFdSegment.of(arena.allocate(Layouts.POLLFD))
                pollFd.setFd(serverFd.value)
                pollFd.setEvents(NativeConstants.POLLIN)

                while (!isGlobalShutdown()) {
                    pollFd.setFd(if (synchronized(admissionGate) { admissionClosed || admissions.size >= maxConnections }) -1 else serverFd.value)
                    val pollRes = raw.poll(pollFd.managed, 1L, POLL_TIMEOUT_MS)
                    val count = pollRes.recover { errno, _ ->
                        if (errno != NativeConstants.EINTR) return@use
                        0L
                    }

                    if (count > 0 && (pollFd.getRevents().toInt() and NativeConstants.POLLIN.toInt()) != 0) {
                        handleNewConnection(serverFd)
                    }
                }
            }
        } finally {
            applyEvent(UnixListenDaemonEvent.AcceptLoopFinished(serverFd))
        }
    }

    public fun triggerGlobalShutdown(source: String = "unknown") {
        synchronized(admissionGate) { admissionClosed = true }
        applyEvent(UnixListenDaemonEvent.ShutdownRequested(source))
    }

    internal fun applyEvent(event: UnixListenDaemonEvent): UnixListenDaemonTransition {
        if (event is UnixListenDaemonEvent.ShutdownRequested || event is UnixListenDaemonEvent.AcceptLoopFinished) {
            synchronized(admissionGate) { admissionClosed = true }
        }
        return UnixListenDaemonMachine.apply(stateRef, event, ::executeEffects).also { transition ->
            when (val outcome = transition.outcome) {
                UnixListenDaemonOutcome.Advanced,
                UnixListenDaemonOutcome.Terminated,
                -> Unit
                is UnixListenDaemonOutcome.BenignObservation ->
                    System.err.println("[SECCOMP-DAEMON] Lifecycle observation: ${outcome.name}")
                is UnixListenDaemonOutcome.ProtocolViolation ->
                    System.err.println("[SECCOMP-DAEMON] Lifecycle protocol violation: ${outcome.state} + ${outcome.event}")
                is UnixListenDaemonOutcome.PostTerminalObservation ->
                    System.err.println("[SECCOMP-DAEMON] Post-terminal lifecycle observation: ${outcome.event}")
            }
        }
    }

    private fun executeEffects(effects: List<UnixListenDaemonEffect>) {
        SeccompCleanupInterpreter.closeResources(effects.map { effect -> { executeEffect(effect) } }, ::recordCleanup)
    }

    private fun executeEffect(effect: UnixListenDaemonEffect) {
            when (effect) {
                is UnixListenDaemonEffect.LogListening ->
                    System.err.println("[SECCOMP-DAEMON] Listening on ${effect.socketPath} (fd=${effect.serverFd})")
                is UnixListenDaemonEffect.PublishReady -> {
                    println(readySentinel)
                    System.out.flush()
                }
                is UnixListenDaemonEffect.LogShutdown ->
                    System.err.println("[SECCOMP-DAEMON] Initiating graceful shutdown. Source: ${effect.source}.")
                is UnixListenDaemonEffect.CloseServer -> {
                    failedServerCloses.addIfAbsent(effect.serverFd)
                    SeccompCleanupInterpreter.closeResources(listOf({ closeServer(effect.serverFd) }), ::recordCleanup)
                }
                is UnixListenDaemonEffect.StopConnectionWorkers -> {
                    val queued = connectionExecutor.shutdownNow().filterIsInstance<OwnedConnectionTask>()
                    SeccompCleanupInterpreter.closeResources(queued.map { task -> { task.retireQueued() } }, ::recordCleanup)
                }
                UnixListenDaemonEffect.AwaitConnectionWorkers -> {
                    try {
                        if (!connectionExecutor.awaitTermination(CONNECTION_WORKER_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                            cleanupFailures += IllegalStateException("Connection workers did not terminate before descriptor cleanup")
                            System.err.println("[SECCOMP-DAEMON] Connection workers did not terminate before owned descriptor cleanup")
                        }
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        cleanupFailures += InterruptedException("Interrupted while waiting for connection workers")
                        System.err.println("[SECCOMP-DAEMON] Interrupted while waiting for connection workers to terminate")
                    }
                }
                is UnixListenDaemonEffect.CloseTrackedConnections -> {
                    if (connectionExecutor.isTerminated) {
                        retryCleanup()
                    }
                }
                // Successful closes remove their entries; failed ownership must remain retryable.
                is UnixListenDaemonEffect.ClearConnectionTables -> Unit
            }
    }

    public fun isGlobalShutdown(): Boolean {
        val curr = state
        return curr is UnixListenDaemonState.ShuttingDown || curr is UnixListenDaemonState.Terminated
    }

    public fun handleNewConnection(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>) {
        val record = reserveAdmission() ?: return
        var submitted = false
        var bound = false
        var clientFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>? = null
        try {
            clientFd = acceptCandidate(serverFd)
            if (clientFd != null && bindAdmission(record, clientFd, true)) {
                bound = true
                if (synchronized(admissionGate) { admissionClosed }) {
                    cleanupFailedConnection(clientFd)
                } else {
                    clientSockets.add(clientFd)
                    connectionExecutor.execute(OwnedConnectionTask(record, clientFd))
                    submitted = true
                }
            }
        } catch (expectedAcceptFailure: Exception) {
            if (bound) cleanupFailedConnection(clientFd)
            handleAcceptFailure(expectedAcceptFailure)
        } catch (expectedAcceptError: Error) {
            if (bound) cleanupFailedConnection(clientFd)
            throw expectedAcceptError
        } finally {
            if (!submitted && !bound) releaseAdmission(record)
        }
    }

    private fun acceptCandidate(
        serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    ): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>? {
        hooks.connectionAcceptor?.let { return it(serverFd) }
        while (true) {
            val result = engine.networking.accept4(serverFd, ManagedSegment.NULL, ManagedSegment.NULL, NativeConstants.SOCK_CLOEXEC or NativeConstants.O_NONBLOCK.toInt())
            val value = result.recover { errno, _ -> if (errno == NativeConstants.EINTR) -1L else -2L }
            if (value == -1L) continue
            return if (value < 0L) null else result.getFdOrThrow("accept client", FileDescriptorRole.UnixSocket)
        }
    }

    private enum class QueuedAction { RUN, RETIRE }

    private inner class OwnedConnectionTask(
        private val record: AdmissionRecord,
        private val socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    ) : Runnable {
        override fun run() = performQueuedAdmission(record, socket, QueuedAction.RUN)

        fun retireQueued() = performQueuedAdmission(record, socket, QueuedAction.RETIRE)
    }

    private fun performQueuedAdmission(
        record: AdmissionRecord,
        socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        action: QueuedAction,
    ) {
        val reserved = synchronized(admissionGate) {
            val current = admissions.any { it === record } && record.socket === socket && !record.taskStarted && !record.queuedRetirement
            when (action) {
                QueuedAction.RUN -> {
                    check(current)
                    record.taskStarted = true
                    true
                }
                QueuedAction.RETIRE -> if (current) {
                    record.queuedRetirement = true
                    true
                } else {
                    false
                }
            }
        }
        if (reserved) {
            when (action) {
            QueuedAction.RUN -> handleOwnedConnection(record, socket)
            QueuedAction.RETIRE -> cleanupFailedConnection(socket)
        }
        }
    }

    private fun cleanupFailedConnection(clientFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>?) {
        clientFd ?: return
        val connection = incompleteConnections.firstOrNull { it.socketFd === clientFd }
            ?: SeccompConnection.Accepted(clientFd).apply { socketManager = this@SeccompDaemonEngine.socketManager }
        synchronized(admissionGate) {
            admissions.firstOrNull { it.socket === clientFd }?.let { if (it.current == null) it.current = connection }
        }
        cleanupConnection(connection)
    }

    private fun closeServer(server: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>) {
        try {
            socketManager.close(server)
        } catch (failure: NativeCloseFailure) {
            failedServerCloses.remove(server)
            throw failure
        }
        failedServerCloses.remove(server)
    }

    private fun recordCleanup(outcome: SeccompCleanupOutcome) {
        when (outcome) {
            SeccompCleanupOutcome.Complete -> Unit
            is SeccompCleanupOutcome.Incomplete -> {
                outcome.failures.forEach { failure ->
                    if (cleanupFailures.none { it === failure }) cleanupFailures.add(failure)
                    System.err.println("[SECCOMP-DAEMON] Incomplete cleanup: $failure")
                }
            }
        }
    }

    private fun advancePendingSession(connection: SeccompConnection) {
        val pair = synchronized(admissionGate) {
            val record = admissions.firstOrNull { it.socket === connection.socketFd }
            if (record == null || record.retirement !is SessionRetirement.Pending || record.advancing) {
                null
            } else {
                record.advancing = true
                record to checkNotNull(record.session)
            }
        } ?: return
        val outcome = pair.second.retireCompletion()
        val stop = synchronized(admissionGate) {
            check(admissions.any { it === pair.first } && pair.first.session === pair.second && pair.first.retirement is SessionRetirement.Pending)
            pair.first.retirement = outcome
            pair.first.advancing = false
            if (outcome is SessionRetirement.Unknown) {
                admissionClosed = true
                true
            } else {
                false
            }
        }
        if (stop) {
            triggerGlobalShutdown("eventfd retirement unknown")
            val failure = (outcome as SessionRetirement.Unknown).failure
            SeccompCleanupInterpreter.closeResources(listOf({ throw failure }), ::recordCleanup)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Cleanup publishes retained ownership before rethrowing the exact fatal cause.
    private fun cleanupConnection(connection: SeccompConnection) {
        synchronized(connection) {
        incompleteConnections.addIfAbsent(connection)
        var published = false
        var publishedOutcome: SeccompCleanupOutcome? = null

        fun publish(outcome: SeccompCleanupOutcome) {
            published = true
            publishedOutcome = outcome
            recordCleanup(outcome)
            hooks.afterConnectionClosed?.invoke(connection.socketFd, outcome)
        }
        try {
            SeccompCleanupInterpreter.close(connection, ::publish)
        } catch (fatal: Error) {
            if (!published && hooks.afterConnectionClosed != null) {
                try {
                    publish(SeccompCleanupOutcome.Incomplete(listOf(IllegalStateException("Fatal connection cleanup", fatal)), connection.retryableOwnership()))
                } catch (publicationFailure: Throwable) {
                    if (publicationFailure !== fatal) fatal.addSuppressed(publicationFailure)
                }
            }
            throw fatal
        } finally {
            handshakeDeadlines.remove(connection)
            val remaining = connection.retryableOwnership()
            val releasable = canReleaseConnection(connection, publishedOutcome)
            if (remaining.isEmpty() && releasable) {
                incompleteConnections.remove(connection)
                synchronized(admissionGate) { admissions.removeAll { it.socket === connection.socketFd } }
            }
            if (connection.socketFd !in remaining) clientSockets.remove(connection.socketFd)
            connection.listenerFd?.takeIf { it !in remaining }?.let { activeListeners.remove(it) }
        }
        }
    }

    private fun canReleaseConnection(
        connection: SeccompConnection,
        outcome: SeccompCleanupOutcome?,
    ): Boolean =
        synchronized(admissionGate) {
        val record = admissions.firstOrNull { it.socket === connection.socketFd }
        if (record != null && outcome is SeccompCleanupOutcome.Incomplete && outcome.failures.any { it is NativeCloseFailure }) {
            record.socketUnknown = true
        }
        record == null || (outcome is SeccompCleanupOutcome.Complete && !record.socketUnknown && record.retirement is SessionRetirement.Complete)
    }

    /** Control reconnect retries its original completed conversation, never another active owner. */
    internal fun retryConnectionCleanup(socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>) {
        incompleteConnections.firstOrNull { it.socketFd === socket }?.let { connection ->
            SeccompCleanupInterpreter.closeResources(listOf({ advancePendingSession(connection) }, { cleanupConnection(connection) }), ::recordCleanup)
        }
    }

    /** Retries only failed owned descriptors; prior failure diagnostics remain available. */
    @io.mazewall.platform.lifecycle.MazewallInternal
    public fun retryCleanup(): SeccompCleanupOutcome {
        val connections = incompleteConnections.toMutableList()
        val releases = connections.flatMap { connection -> listOf({ advancePendingSession(connection) }, { cleanupConnection(connection) }) } +
            failedServerCloses.map { server ->
                {
                    closeServer(server)
                }
            }
        SeccompCleanupInterpreter.closeResources(releases, ::recordCleanup)
        return cleanupOutcome
    }

    @Suppress("TooGenericExceptionCaught") // retain the original admitted socket and preserve the first fatal failure
    public fun handleConnection(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>) {
        val record = reserveAdmission() ?: return
        if (!bindAdmission(record, socketFd, false)) {
            releaseAdmission(record)
            return
        }
        synchronized(admissionGate) {
            check(admissions.any { it === record } && record.socket === socketFd && !record.taskStarted && !record.queuedRetirement)
            record.taskStarted = true
        }
        try {
            if (clientSockets.none { it === socketFd }) clientSockets.add(socketFd)
            handleOwnedConnection(record, socketFd)
        } catch (problem: Throwable) {
            val unstarted = synchronized(admissionGate) { admissions.any { it === record } && record.socket === socketFd && record.current == null }
            if (unstarted) {
                var retained = problem
                try {
                    cleanupFailedConnection(socketFd)
                } catch (cleanup: Throwable) {
                    retained = CleanupFailures.merge(retained, cleanup)
                }
                throw retained
            }
            throw problem
        }
    }

    private fun handleOwnedConnection(
        record: AdmissionRecord,
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    ) {
        val connection: SeccompConnection = SeccompConnection.Accepted(socketFd).apply {
            this.socketManager = this@SeccompDaemonEngine.socketManager
        }
        synchronized(admissionGate) {
            check(admissions.any { it === record } && record.socket === socketFd && record.current == null)
            record.current = connection
            record.reactor = Thread.currentThread()
        }
        var interrupted = false

        try {
            runOwnedProtocol(record, socketFd, connection)
        } catch (expectedInterrupt: InterruptedException) {
            System.err.println("[SECCOMP-DAEMON] Connection handler interrupted: ${expectedInterrupt.message}")
            interrupted = true
        } catch (expectedInterruptedChannel: java.nio.channels.ClosedByInterruptException) {
            System.err.println("[SECCOMP-DAEMON] Connection handler channel closed by interrupt: ${expectedInterruptedChannel.message}")
            interrupted = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failedClose: NativeCloseFailure) {
            recordCleanup(SeccompCleanupOutcome.Incomplete(listOf(failedClose), emptyList()))
        } catch (expectedConnectionFailure: Exception) {
            System.err.println("[SECCOMP-DAEMON-WARN] Connection handler terminated with exception: ${expectedConnectionFailure.message}")
        } finally {
            try {
                val current = synchronized(admissionGate) { record.current ?: connection }
                cleanupConnection(current)
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }

    private fun runOwnedProtocol(
        record: AdmissionRecord,
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        initial: SeccompConnection,
    ) {
            if (synchronized(admissionGate) { admissionClosed } || isGlobalShutdown()) return
            val prelude = hooks.beforeListener?.invoke(socketFd, Deadline.afterMillis(PRELUDE_TIMEOUT_MS))
            val live = (prelude as? PreludeOutcome.LiveListener)?.admission
            try {
                when (prelude) {
                    PreludeOutcome.LegacyListener, null -> Unit
                    PreludeOutcome.Handled -> return
                    is PreludeOutcome.LiveListener -> {
                        synchronized(admissionGate) {
                            check(admissions.any { it === record } && record.socket === socketFd && record.liveAdmission == null)
                            record.liveAdmission = prelude.admission
                        }
                    }
                }
                var connection = initial
                NativeArena.ofConfined().use { arena ->
                val pollFd = PollFdSegment.of(arena.allocate(Layouts.POLLFD))
                pollFd.setFd(socketFd.value)
                pollFd.setEvents(NativeConstants.POLLIN)
                val pollFdManaged = pollFd.managed

                while (!isGlobalShutdown()) {
                    val next = processConnectionStep(arena, connection, socketFd, pollFdManaged, record) ?: break
                    connection = next
                }
                }
            } finally {
                live?.close()
            }
    }

    public fun processConnectionStep(
        arena: NativeArena,
        connection: SeccompConnection,
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        pollFdManaged: ManagedSegment,
    ): SeccompConnection? = processConnectionStep(arena, connection, socketFd, pollFdManaged, null)

    private fun processConnectionStep(
        arena: NativeArena,
        connection: SeccompConnection,
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        pollFdManaged: ManagedSegment,
        record: AdmissionRecord?,
    ): SeccompConnection? {
        val handshakeDeadline = if (connection is SeccompConnection.FdAttached) {
            handshakeDeadlines.getOrCreate(connection) { Deadline.afterMillis(HANDSHAKE_ACK_TIMEOUT_MS) }
        } else {
            null
        }
        if (handshakeDeadline?.isExpired() == true) {
            handshakeDeadlines.remove(connection)
            return applyConnectionEvent(connection, SeccompConnectionEvent.AckFailed, record)
        }
        if (connection !is SeccompConnection.Active) {
            val pollFd = PollFdSegment.of(pollFdManaged)
            val expectedEvent = if (connection is SeccompConnection.FdAttached) NativeConstants.POLLOUT else NativeConstants.POLLIN
            pollFd.setFd(socketFd.value)
            pollFd.setEvents(expectedEvent)
            pollFd.setRevents(0)
            val timeout = handshakeDeadline?.remainingMillis()?.coerceAtMost(POLL_TIMEOUT_MS) ?: POLL_TIMEOUT_MS
            val pollRes = raw.poll(pollFdManaged, 1L, timeout)
            val count = pollRes.recover { errno, _ ->
                if (errno == NativeConstants.EINTR) return connection
                -1L
            }
            if (count < 0) {
                handshakeDeadlines.remove(connection)
                val failure = if (connection is SeccompConnection.FdAttached) SeccompConnectionEvent.AckFailed else SeccompConnectionEvent.PollFailed
                return applyConnectionEvent(connection, failure, record)
            }
            if (count == 0L) {
                if (handshakeDeadline?.isExpired() == true) {
                    handshakeDeadlines.remove(connection)
                    return applyConnectionEvent(connection, SeccompConnectionEvent.AckFailed, record)
                }
                return if (connection is SeccompConnection.Accepted) {
                    applyConnectionEvent(connection, SeccompConnectionEvent.PollIdle, record)
                } else {
                    connection
                }
            }
            // A real poll always writes revents when it reports a ready descriptor. Some
            // injected/native test pollers only return the ready count, however; treating an
            // empty mask as the event we requested keeps the reactor seam deterministic without
            // changing kernel behaviour.
            val revents = pollFd.getRevents().toInt().let { value ->
                if (value == 0 && count > 0L) expectedEvent.toInt() else value
            }
            val terminal = NativeConstants.POLLERR.toInt() or NativeConstants.POLLHUP.toInt() or NativeConstants.POLLNVAL.toInt()
            if ((revents and terminal) != 0) {
                handshakeDeadlines.remove(connection)
                val failure = if (connection is SeccompConnection.FdAttached) SeccompConnectionEvent.AckFailed else SeccompConnectionEvent.PollFailed
                return applyConnectionEvent(connection, failure, record)
            }
            if ((revents and expectedEvent.toInt()) == 0) return connection
        }

        val event = when (connection) {
            is SeccompConnection.Accepted -> {
                val listenerFd = socketManager.recvDescriptor(socketFd)
                if (listenerFd != null) {
                    SeccompConnectionEvent.ListenerReceived(listenerFd)
                } else {
                    SeccompConnectionEvent.RecvFailed
                }
            }
            is SeccompConnection.FdAttached -> {
                val ackBuf = arena.allocate(ACK_BUF_SIZE)
                ackBuf.writeByte(0L, handshakeAckByte)
                val deadline = checkNotNull(handshakeDeadline)
                when (writeHandshakeAck(socketFd, ackBuf, deadline)) {
                    HandshakeWriteOutcome.Sent -> {
                        handshakeDeadlines.remove(connection)
                        SeccompConnectionEvent.AckSucceeded
                    }
                    HandshakeWriteOutcome.RetryAfterPoll -> return connection
                    HandshakeWriteOutcome.TimedOut,
                    is HandshakeWriteOutcome.Failed,
                    -> {
                        handshakeDeadlines.remove(connection)
                        SeccompConnectionEvent.AckFailed
                    }
                }
            }
            is SeccompConnection.Active -> SeccompConnectionEvent.SessionFinished
        }
        return applyConnectionEvent(connection, event, record)
    }

    /**
     * Writes the one-byte handshake acknowledgement without allowing EINTR to turn into an
     * unbounded synchronous loop. The deadline is absolute and is never renewed by retries.
     */
    internal fun writeHandshakeAck(
        socketFd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>,
        ack: ManagedSegment,
        deadline: Deadline,
    ): HandshakeWriteOutcome {
        if (deadline.isExpired()) return HandshakeWriteOutcome.TimedOut
        return when (val result = handshakeWriter(socketFd, ack, ACK_BUF_SIZE)) {
            is LinuxNative.SyscallResult.Success -> if (result.value == ACK_BUF_SIZE) {
                HandshakeWriteOutcome.Sent
            } else {
                HandshakeWriteOutcome.Failed(NativeConstants.EIO)
            }
            is LinuxNative.SyscallResult.Error -> when (result.errno) {
                NativeConstants.EAGAIN, NativeConstants.EINTR -> HandshakeWriteOutcome.RetryAfterPoll
                else -> HandshakeWriteOutcome.Failed(result.errno)
            }
        }
    }

    private fun applyConnectionEvent(
        connection: SeccompConnection,
        event: SeccompConnectionEvent,
        record: AdmissionRecord?,
    ): SeccompConnection? {
        val transition = SeccompConnectionMachine.evaluate(connection, event)
        if (record != null) {
            synchronized(admissionGate) {
            check(admissions.any { it === record } && record.current === connection && record.reactor === Thread.currentThread())
            transition.connection?.let { record.current = it }
        }
        }
        when (val outcome = transition.outcome) {
            SeccompConnectionOutcome.Advanced,
            SeccompConnectionOutcome.IdlePoll,
            SeccompConnectionOutcome.Terminated,
            -> Unit
            is SeccompConnectionOutcome.ProtocolViolation ->
                System.err.println("[SECCOMP-DAEMON] Terminating malformed connection protocol: ${outcome.state} + ${outcome.event}")
        }
        for (effect in transition.effects) {
            when (effect) {
                is SeccompConnectionEffect.RegisterListener -> {
                    System.err.println("[SECCOMP-DAEMON] Received listener FD: ${effect.listenerFd.value}")
                    activeListeners.add(effect.listenerFd)
                }
                is SeccompConnectionEffect.LogAck ->
                    System.err.println("[SECCOMP-DAEMON] Sending handshake ACK to socket ${connection.socketFd.value}")
                is SeccompConnectionEffect.RunSession -> {
                    val active = connection as SeccompConnection.Active
                    System.err.println("[SECCOMP-DAEMON] Starting session reactor for listener ${active.listenerFd.value}")
                    checkNotNull(record) { "Session requires an admitted original connection" }
                    handleSession(active, record)
                    System.err.println("[SECCOMP-DAEMON] Session reactor finished. Closing connection.")
                }
                is SeccompConnectionEffect.CloseRejectedListener ->
                    SeccompCleanupInterpreter.closeResources(
                        listOf({ socketManager.close(effect.listenerFd) }),
                        ::recordCleanup,
                    )
            }
        }
        return transition.connection?.also { it.socketManager = socketManager }
    }

    private fun handleSession(
        connection: SeccompConnection.Active,
        record: AdmissionRecord,
    ) {
        synchronized(admissionGate) {
            check(admissions.any { it === record } && record.current === connection && record.socket === connection.socketFd && record.reactor === Thread.currentThread() && record.session == null)
        }
        val socketFd = connection.socketFd
        val listenerFd = connection.listenerFd
        val live = record.liveAdmission
        val notifHandler = if (live == null) {
            notifHandlerFactory(socketFd, listenerFd)
        } else {
            checkNotNull(hooks.liveListenerFactory) { "Live listener factory unavailable" }(live, socketFd, listenerFd)
        }
        try {
            val sessionHandler = SeccompSessionHandler(
                socketFd = socketFd,
                listenerFd = listenerFd,
                notifHandler = notifHandler,
                onShutdown = this::triggerGlobalShutdown,
                onSocketClosed = connection::markSocketClosed,
                engine = engine,
                socketManager = socketManager,
            )
            synchronized(admissionGate) {
                check(admissions.any { it === record } && record.current === connection && record.session == null && record.reactor === Thread.currentThread())
                record.session = sessionHandler
            }
            try {
                runSessionLoop(sessionHandler, socketFd, listenerFd)
            } finally {
                sessionHandler.closeWithResult { outcome -> publishSessionOutcome(record, connection, sessionHandler, outcome) }
            }
        } finally {
            SeccompCleanupInterpreter.closeResources(listOf({ (notifHandler as? AutoCloseable)?.close() }), ::recordCleanup)
            // The connection owner's finally block interprets listener descriptor cleanup.
        }
    }

    private fun publishSessionOutcome(
        record: AdmissionRecord,
        connection: SeccompConnection.Active,
        session: SeccompSessionHandler,
        outcome: SeccompCleanupOutcome,
    ) {
        val retirement = checkNotNull(session.completionRetirementSnapshot())
        val stop = synchronized(admissionGate) {
            check(admissions.any { it === record } && record.current === connection && record.session === session && record.reactor === Thread.currentThread())
            record.retirement = retirement
            if (retirement is SessionRetirement.Unknown) {
                admissionClosed = true
                true
            } else {
                false
            }
        }
        recordCleanup(outcome)
        if (stop) triggerGlobalShutdown("eventfd retirement unknown")
    }

    private fun runSessionLoop(
        sessionHandler: SeccompSessionHandler,
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
    ) {
                NativeArena.ofConfined().use { sessionArena ->
                    val pollFds = sessionArena.allocate(Layouts.POLLFD, 3)
                    val pfd1 = PollFdSegment.of(pollFds.asSlice(0L, Layouts.POLLFD_SIZE))
                    pfd1.setFd(listenerFd.value)
                    pfd1.setEvents(NativeConstants.POLLIN)

                    val pfd2 = PollFdSegment.of(pollFds.asSlice(POLLFD_STRUCT_SIZE, Layouts.POLLFD_SIZE))
                    pfd2.setFd(socketFd.value)
                    pfd2.setEvents(NativeConstants.POLLIN)

                    val pfd3 = PollFdSegment.of(pollFds.asSlice(POLLFD_STRUCT_SIZE * 2, Layouts.POLLFD_SIZE))
                    pfd3.setFd(sessionHandler.completionWakeupPollFd)
                    pfd3.setEvents(NativeConstants.POLLIN)

                    while (!isGlobalShutdown()) {
                        // Expiration and helper completions are reactor events even on idle/EINTR polls.
                        pfd1.setRevents(0)
                        pfd2.setRevents(0)
                        pfd3.setRevents(0)
                        val pollRes = raw.poll(pollFds, 3L, sessionHandler.pollTimeoutMillis())
                        val count = pollRes.recover { errno, _ ->
                            if (errno != NativeConstants.EINTR) return@use
                            0L
                        }
                        if (count <= 0) {
                            pfd1.setRevents(0)
                            pfd2.setRevents(0)
                            pfd3.setRevents(0)
                        }

                        var shouldBreak = false
                        NativeArena.ofConfined().use { iterationArena ->
                            val action = with(iterationArena) {
                                sessionHandler.handleActiveListener(pollFds)
                            }
                            when (action) {
                                is LoopAction.Shutdown -> {
                                    triggerGlobalShutdown("session reactor shutdown action")
                                    shouldBreak = true
                                }
                                is LoopAction.Break -> {
                                    shouldBreak = true
                                }
                                is LoopAction.Continue -> {}
                            }
                        }
                        if (shouldBreak) break
                        if (isGlobalShutdown()) break
                    }
                }
    }
}

private fun handleAcceptFailure(failure: Exception) {
    when (failure) {
        is InterruptedException, is java.nio.channels.ClosedByInterruptException -> Thread.currentThread().interrupt()
        is CancellationException -> throw failure
        else -> Unit
    }
}
