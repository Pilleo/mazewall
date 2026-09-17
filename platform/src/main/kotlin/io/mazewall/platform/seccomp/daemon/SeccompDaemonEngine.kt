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
    private val connectionAcceptor: (
        (
        FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    ) -> FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>
    )? = null,
) {
    private val connectionExecutor: ExecutorService = Executors.newFixedThreadPool(maxConnections) { r ->
        Thread(r).apply {
            isDaemon = true
            name = "seccomp-conn"
        }
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
        get() = if (cleanupFailures.isEmpty() && incompleteConnections.isEmpty() && failedServerCloses.isEmpty()) {
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
        applyEvent(UnixListenDaemonEvent.ShutdownRequested(source))
    }

    internal fun applyEvent(event: UnixListenDaemonEvent): UnixListenDaemonTransition {
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
                is UnixListenDaemonEffect.StopConnectionWorkers ->
                    connectionExecutor.shutdownNow()
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
        var clientFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>? = null
        try {
            while (true) {
                if (connectionAcceptor != null) {
                    clientFd = connectionAcceptor.invoke(serverFd)
                } else {
                    val res = engine.networking.accept4(
                        serverFd,
                        ManagedSegment.NULL,
                        ManagedSegment.NULL,
                        NativeConstants.SOCK_CLOEXEC or NativeConstants.O_NONBLOCK.toInt(),
                    )
                    val clientFdVal = res.recover { errno, _ ->
                        if (errno == NativeConstants.EINTR) return@recover -1L
                        -2L
                    }

                    if (clientFdVal == -1L) {
                        continue
                    }

                    if (clientFdVal < 0L) return
                    clientFd = res.getFdOrThrow("accept client", FileDescriptorRole.UnixSocket)
                }

                if (clientSockets.size >= maxConnections) {
                    System.err.println("[SECCOMP-DAEMON] Rejecting connection: limit reached (${clientSockets.size})")
                    cleanupFailedConnection(clientFd)
                    return
                }

                clientSockets.add(clientFd)

                // Spawn a new thread to handle the connection concurrently!
                connectionExecutor.submit {
                    handleConnection(clientFd)
                }
                return
            }
        } catch (_: InterruptedException) {
            cleanupFailedConnection(clientFd)
            Thread.currentThread().interrupt()
        } catch (_: java.nio.channels.ClosedByInterruptException) {
            cleanupFailedConnection(clientFd)
            Thread.currentThread().interrupt()
        } catch (cancelled: CancellationException) {
            cleanupFailedConnection(clientFd)
            throw cancelled
        } catch (expectedAcceptFailure: Exception) {
            cleanupFailedConnection(clientFd)
        } catch (expectedAcceptError: Error) {
            cleanupFailedConnection(clientFd)
            throw expectedAcceptError
        }
    }

    private fun cleanupFailedConnection(clientFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>?) {
        clientFd ?: return
        clientSockets.addIfAbsent(clientFd)
        val connection = incompleteConnections.firstOrNull { it.socketFd == clientFd }
            ?: SeccompConnection.Accepted(clientFd).apply { socketManager = this@SeccompDaemonEngine.socketManager }
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
                cleanupFailures.addAllAbsent(outcome.failures)
                outcome.failures.forEach { System.err.println("[SECCOMP-DAEMON] Incomplete cleanup: $it") }
            }
        }
    }

    private fun cleanupConnection(connection: SeccompConnection) {
        incompleteConnections.addIfAbsent(connection)
        try {
            SeccompCleanupInterpreter.close(connection, ::recordCleanup)
        } finally {
            handshakeDeadlines.remove(connection)
            val remaining = connection.retryableOwnership()
            if (connection.socketFd !in remaining) clientSockets.remove(connection.socketFd)
            connection.listenerFd?.takeIf { it !in remaining }?.let { activeListeners.remove(it) }
            if (remaining.isEmpty()) incompleteConnections.remove(connection)
        }
    }

    /** Retries only failed owned descriptors; prior failure diagnostics remain available. */
    @io.mazewall.platform.lifecycle.MazewallInternal
    @Synchronized
    public fun retryCleanup(): SeccompCleanupOutcome {
        val connections = incompleteConnections.toMutableList()
        if (connectionExecutor.isTerminated) {
            clientSockets.filter { socket -> connections.none { it.socketFd == socket } }.forEach { socket ->
                connections += SeccompConnection.Accepted(socket).apply { socketManager = this@SeccompDaemonEngine.socketManager }
            }
        }
        val releases = connections.map { connection -> { cleanupConnection(connection) } } +
            failedServerCloses.map { server ->
                {
                    closeServer(server)
                }
            }
        SeccompCleanupInterpreter.closeResources(releases, ::recordCleanup)
        return cleanupOutcome
    }

    public fun handleConnection(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>) {
        var connection: SeccompConnection = SeccompConnection.Accepted(socketFd).apply {
            this.socketManager = this@SeccompDaemonEngine.socketManager
        }
        var interrupted = false

        try {
            NativeArena.ofConfined().use { arena ->
                val pollFd = PollFdSegment.of(arena.allocate(Layouts.POLLFD))
                pollFd.setFd(socketFd.value)
                pollFd.setEvents(NativeConstants.POLLIN)
                val pollFdManaged = pollFd.managed

                while (!isGlobalShutdown()) {
                    val next = processConnectionStep(arena, connection, socketFd, pollFdManaged) ?: break
                    connection = next
                }
            }
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
                cleanupConnection(connection)
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }

    public fun processConnectionStep(
        arena: NativeArena,
        connection: SeccompConnection,
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        pollFdManaged: ManagedSegment,
    ): SeccompConnection? {
        val handshakeDeadline = if (connection is SeccompConnection.FdAttached) {
            handshakeDeadlines.getOrCreate(connection) { Deadline.afterMillis(HANDSHAKE_ACK_TIMEOUT_MS) }
        } else {
            null
        }
        if (handshakeDeadline?.isExpired() == true) {
            handshakeDeadlines.remove(connection)
            return applyConnectionEvent(connection, SeccompConnectionEvent.AckFailed)
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
                return applyConnectionEvent(connection, failure)
            }
            if (count == 0L) {
                if (handshakeDeadline?.isExpired() == true) {
                    handshakeDeadlines.remove(connection)
                    return applyConnectionEvent(connection, SeccompConnectionEvent.AckFailed)
                }
                return if (connection is SeccompConnection.Accepted) {
                    applyConnectionEvent(connection, SeccompConnectionEvent.PollIdle)
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
                return applyConnectionEvent(connection, failure)
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
        return applyConnectionEvent(connection, event)
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
    ): SeccompConnection? {
        val transition = SeccompConnectionMachine.evaluate(connection, event)
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
                    handleSession(active)
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

    private fun handleSession(connection: SeccompConnection.Active) {
        val socketFd = connection.socketFd
        val listenerFd = connection.listenerFd
        val notifHandler = notifHandlerFactory(socketFd, listenerFd)
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
            try {
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
            } finally {
                sessionHandler.closeWithResult(::recordCleanup)
            }
        } finally {
            SeccompCleanupInterpreter.closeResources(listOf({ (notifHandler as? AutoCloseable)?.close() }), ::recordCleanup)
            // The connection owner's finally block interprets listener descriptor cleanup.
        }
    }
}
