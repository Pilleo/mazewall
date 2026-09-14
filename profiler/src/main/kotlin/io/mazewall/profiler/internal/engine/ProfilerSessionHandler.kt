package io.mazewall.profiler.internal.engine

import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.fill
import io.mazewall.ffi.memory.readByte
import io.mazewall.ffi.memory.readInt
import io.mazewall.ffi.memory.readLong
import io.mazewall.ffi.memory.readShort
import io.mazewall.ffi.memory.unwrap
import io.mazewall.ffi.memory.writeInt
import io.mazewall.ffi.memory.writeShort
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.Deadline
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.map
import io.mazewall.platform.native.onSuccess
import io.mazewall.platform.native.recover
import io.mazewall.platform.seccomp.UserNotifReply
import io.mazewall.platform.seccomp.UserNotifReplyResult
import io.mazewall.platform.seccomp.daemon.ControlSocketExchange
import io.mazewall.platform.seccomp.daemon.ControlSocketFailure
import io.mazewall.platform.seccomp.daemon.LoopAction
import io.mazewall.platform.seccomp.daemon.NotifResult
import io.mazewall.platform.seccomp.daemon.NotificationProgress
import io.mazewall.platform.seccomp.daemon.NotificationWork
import io.mazewall.platform.seccomp.daemon.SeccompNotifHandler
import io.mazewall.profiler.api.PathResolution
import io.mazewall.profiler.api.PathResolutionCause
import io.mazewall.profiler.api.PathResolutionFailure
import io.mazewall.profiler.api.SyscallEvent
import io.mazewall.profiler.api.SyscallEventState
import io.mazewall.profiler.api.TraceEvent
import io.mazewall.profiler.api.combinePathResolution
import io.mazewall.profiler.ffi.HandshakeSession
import io.mazewall.profiler.ffi.NativeIoOperations
import io.mazewall.profiler.ffi.SeccompReplyResult
import io.mazewall.profiler.ffi.SeccompResponder
import io.mazewall.profiler.ffi.TraceEventPublisher
import io.mazewall.profiler.ffi.controlMemory
import java.io.IOException

/**
 * Internal logic for handling active seccomp listeners and shutdown requests.
 */
internal class ProfilerSessionHandler(
    private val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    private val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
    private val publisher: TraceEventPublisher,
    private val responder: SeccompResponder,
    private val ioOps: NativeIoOperations,
    private val memoryReader: ProfilerMemoryReader,
    private val syscallMap: Map<Int, String>,
    private val parser: SeccompNotificationParser = RealSeccompNotificationParser,
    private val preparation: ProfilerPreparation? = null,
    private val onShutdown: (String) -> Unit,
) : AutoCloseable,
    SeccompNotifHandler {
    val ledger = SessionEventLedger()

    private val sessionArena = NativeArena.ofConfined()
    val ackBuf: ManagedSegment = sessionArena.allocate(ACK_BUF_SIZE)
    val socketPollFd: ManagedSegment = sessionArena.allocate(Layouts.POLLFD)

    private val resolver = SyscallPathResolver(memoryReader, ledger)

    var state: ProfilerState = ProfilerState.ActiveSession(socketFd, listenerFd)
        private set

    override fun close() {
        sessionArena.close()
    }

    @Suppress("TooGenericExceptionCaught") // Accepted notifications must be settled even when an injected parser or reader fails.
    context(arena: NativeArena) override fun beginNotification(
        notif: ManagedSegment,
        resp: ManagedSegment,
        listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        deadline: Deadline,
    ): NotificationWork {
        val notification = try {
            parser.readNotif(notif)
        } catch (failure: Exception) {
            logSessionFailure("Malformed notification", failure)
            terminateSession()
            return object : NotificationWork {
                context(arena: NativeArena) override fun poll(): NotificationProgress =
                    NotificationProgress.Ready(
                        reply = {
                            UserNotifReply.encodeError(resp, notif.readLong(Layouts.SECCOMP_NOTIF_ID_OFFSET), NativeConstants.EPERM)
                            UserNotifReply.send(ioOps.raw, listenerFd, resp)
                        },
                        result = NotifResult.TERMINATE,
                    )
            }
        }
        return object : NotificationWork {
            private var control: ControlSocketExchange? = null
            private var waiting: ProfilerState? = null
            private var sentRecorded = false
            private var preparing: ProfilerPreparationJob? = null

            private var completed = false

            context(arena: NativeArena) override fun poll(): NotificationProgress {
                check(!completed) { "Notification work is already terminal" }
                return try {
                    advance()
                } catch (failure: Exception) {
                    logSessionFailure("Notification preparation failed", failure)
                    terminateSession()
                    reply(NativeConstants.EPERM, NotifResult.TERMINATE)
                }
            }

            override fun close() {
                if (!completed) terminateSession()
                completed = true
                preparing?.close()
            }

            // SUPPRESSION-RATIONALE: Preparation, publication and ACK each yield to the reactor; terminal outcomes return immediately to exclude another reply attempt.
            context(arena: NativeArena) private fun advance(): NotificationProgress {
                val exchange = control
                if (exchange == null) {
                    val current = state as? ProfilerState.ActiveSession ?: return reply(ECONNRESET, NotifResult.TERMINATE)
                    val job = preparing ?: run {
                        ledger.record(SessionEvent.Notified(System.nanoTime(), notification.pid.toLong(), notification.nr.toLong(), notification.arch))
                        checkNotNull(preparation) { "Profiler preparation worker is unavailable" }
                            .submit(notification, syscallMap[notification.nr] ?: "SYSCALL_${notification.nr}")
                            .also { preparing = it }
                    }
                    if (!job.result.isDone) return NotificationProgress.Await
                    val event = job.result.join()
                    if (isNoisePath(notification.nr, event)) {
                        state = ProfilerSessionMachine.evaluate(current, ProfilerSessionEvent.NoisePathBypassed).state
                        return reply(0, NotifResult.HANDLED)
                    }
                    val frame = io.mazewall.profiler.internal.TraceEventEncoder
                        .encode(event)
                    control = ControlSocketExchange(ioOps.raw, ioOps.controlMemory(), socketFd, frame, ACK_BUF_SIZE.toInt(), deadline, minimumResponseSize = 1)
                    waiting = transitionToAckWait(current, notification.id, event)
                    state = checkNotNull(waiting)
                    return NotificationProgress.Await
                }
                val bytes = try {
                    val received = exchange.poll()
                    if (exchange.requestSent && !sentRecorded) {
                        sentRecorded = true
                        ledger.record(SessionEvent.EventSent(System.nanoTime(), notification.pid.toLong()))
                    }
                    received ?: return NotificationProgress.Await
                } catch (failure: ControlSocketFailure) {
                    logSessionFailure("ACK transport failed", failure)
                    state = ProfilerSessionMachine.evaluate(checkNotNull(waiting), ProfilerSessionEvent.HandshakeFailed).state
                    return reply(ECONNRESET, NotifResult.TERMINATE)
                }
                return when (val ack = ProfilerAckOutcome.decode(bytes)) {
                    ProfilerAckOutcome.PassThrough -> {
                        state = ProfilerSessionMachine.evaluate(checkNotNull(waiting), ProfilerSessionEvent.PassedThrough).state
                        reply(0, NotifResult.PASS_THROUGH)
                    }
                    is ProfilerAckOutcome.Acknowledged -> {
                        ledger.record(SessionEvent.AckReceived(System.nanoTime(), notification.pid.toLong()))
                        state = ProfilerSessionMachine.evaluate(checkNotNull(waiting), ProfilerSessionEvent.AckSucceeded).state
                        reply(0, NotifResult.HANDLED, ack.shutdown)
                    }
                    is ProfilerAckOutcome.Rejected -> {
                        ledger.record(SessionEvent.ReplyFailed(System.nanoTime(), notification.pid.toLong(), ack.reason.name, null))
                        state = ProfilerSessionMachine.evaluate(checkNotNull(waiting), ProfilerSessionEvent.HandshakeFailed).state
                        reply(ECONNRESET, NotifResult.TERMINATE, ack.reason == ProfilerHandshakeFailure.Shutdown)
                    }
                }
            }

            private fun reply(
                errno: Int,
                result: NotifResult,
                shutdown: Boolean = false,
            ): NotificationProgress =
                NotificationProgress.Ready(
                reply = {
                    if (errno == 0) {
                        UserNotifReply.encodeContinue(resp, notification.id)
                    } else {
                        UserNotifReply.encodeError(resp, notification.id, errno)
                    }
                    UserNotifReply.send(ioOps.raw, listenerFd, resp)
                },
                result = result,
                onReply = { outcome ->
                    completed = true
                    when (outcome) {
                    UserNotifReplyResult.Sent -> {
                        if (errno == 0) {
                            ledger.record(SessionEvent.ContinueReplied(System.nanoTime(), notification.pid.toLong(), 0))
                        } else {
                            ledger.record(SessionEvent.ErrorReplied(System.nanoTime(), notification.pid.toLong(), errno))
                        }
                        if (shutdown) onShutdown("Parent Command (deferred, ACK took priority)")
                    }
                    UserNotifReplyResult.NotificationGone -> {
                        ledger.record(SessionEvent.ReplyFailed(System.nanoTime(), notification.pid.toLong(), "notification-gone", null))
                        terminateSession()
                    }
                    is UserNotifReplyResult.Failed -> {
                        ledger.record(SessionEvent.ReplyFailed(System.nanoTime(), notification.pid.toLong(), "SECCOMP_IOCTL_NOTIF_SEND", outcome.errno))
                        terminateSession()
                    }
                    }
                },
            )
        }
    }

    /**
     * Processes a single incoming seccomp notification.
     *
     * ### ⚠️ FFM Memory Safety & Lifetime Invariant:
     * To prevent off-heap/native memory segment leaks, high GC pressure, and lifetime issues,
     * any raw seccomp_data structural slices or other native/off-heap memory resolved inside this method
     * **must be strictly materialized into JVM heap objects** before crossing the [TraceEvent] or
     * [SyscallEvent] boundaries (which is done here when [SyscallPathResolver.resolve] is called
     * and the resulting event is published via [TraceEventPublisher.sendTraceEvent]).
     *
     * ### 🚀 MM Optimization & Arena Reuse:
     * This method reuses the context-passed `arena` (which is the short-lived `iterationArena`
     * managed and closed at the end of every loop iteration in [ProfilerDaemonEngine.handleSession]).
     * This ensures that all transient native allocations (e.g., string reading, socket polling structures)
     * are deterministically freed when the iteration completes, completely eliminating the overhead of
     * creating a new confined arena per notification or operation.
     *
     * Each early result below is a terminal exactly-one-reply classification. Keeping that classification
     * explicit prevents ACK, notification-gone, interruption, and transport paths from replying twice.
     */
    context(arena: NativeArena) fun processNotification(
        notif: ManagedSegment,
        resp: ManagedSegment,
        listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
        // SUPPRESSION-RATIONALE: The migration signature retains this parameter; the active session owns the endpoint and never fabricates ownership.
        @Suppress("UnusedParameter") socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    ): NotifResult {
        val currentState = state as? ProfilerState.ActiveSession ?: return NotifResult.TERMINATE

        val parsedNotif = parser.readNotif(notif)
        val id = parsedNotif.id
        val pidVal = parsedNotif.pid

        val handshake = HandshakeSession.Active(id, listenerFd)
        val reply = ReplyAttempt()

        return try {
            handleActiveNotification(
                currentState,
                parsedNotif,
                handshake,
                resp,
                reply,
                Deadline.afterMillis(POLL_ACK_TIMEOUT_MS.toLong()),
            )
        } catch (e: InterruptedException) {
            terminateSession()
            Thread.currentThread().interrupt()
            throw e
        } catch (e: java.nio.channels.ClosedByInterruptException) {
            terminateSession()
            Thread.currentThread().interrupt()
            throw e
        } catch (e: IOException) {
            logSessionFailure("IOException", e)
            failTransport(handshake, resp, pidVal, reply.attempted)
        } catch (expectedStructuralFailure: Exception) {
            terminateSession()
            logSessionFailure("Structural or unrecoverable error", expectedStructuralFailure)
            if (reply.attempted) NotifResult.TERMINATE else throw expectedStructuralFailure
        } catch (expectedStructuralError: Error) {
            terminateSession()
            logSessionFailure("Structural or unrecoverable error", expectedStructuralError)
            throw expectedStructuralError
        }
    }

    context(arena: NativeArena) private fun handleActiveNotification(
        currentState: ProfilerState.ActiveSession,
        notification: SeccompNotification,
        handshake: HandshakeSession.Active,
        response: ManagedSegment,
        reply: ReplyAttempt,
        deadline: Deadline,
    ): NotifResult {
        ledger.record(SessionEvent.Notified(System.nanoTime(), notification.pid.toLong(), notification.nr.toLong(), notification.arch))
        val event = resolveNotificationEvent(notification.pid, notification.nr, notification.args)
        if (isNoisePath(notification.nr, event)) {
            state = ProfilerSessionMachine.evaluate(currentState, ProfilerSessionEvent.NoisePathBypassed).state
            reply.attempted = true
            val sent = with(arena.unwrap) { responder.sendSeccompContinue(handshake.acknowledged(), response.unwrap) }
            return if (sent == SeccompReplyResult.NOTIFICATION_GONE) terminateSession() else NotifResult.HANDLED
        }
        val waitingState = transitionToAckWait(currentState, notification.id, event)
        state = waitingState
        val result = deliverAndAwaitAck(notification.pid, event, handshake, deadline)
        return handleHandshakeResult(result, waitingState, response, notification.pid, reply)
    }

    context(arena: NativeArena) private fun handleHandshakeResult(
        result: HandshakeSession,
        waitingState: ProfilerState,
        response: ManagedSegment,
        pid: Int,
        reply: ReplyAttempt,
    ): NotifResult =
        when (result) {
            is HandshakeSession.Success -> handleSuccessfulHandshake(result, waitingState, response, pid, reply)
            is HandshakeSession.Failed -> handleFailedHandshake(result, waitingState, response, pid, reply)
            is HandshakeSession.PassedThrough -> handlePassedThrough(result, waitingState, response, pid, reply)
            is HandshakeSession.Active -> {
                state = ProfilerSessionMachine.evaluate(waitingState, ProfilerSessionEvent.HandshakeFailed).state
                NotifResult.TERMINATE
            }
        }

    context(arena: NativeArena) private fun handleSuccessfulHandshake(
        result: HandshakeSession.Success,
        waitingState: ProfilerState,
        response: ManagedSegment,
        pid: Int,
        reply: ReplyAttempt,
    ): NotifResult {
        ledger.record(SessionEvent.AckReceived(System.nanoTime(), pid.toLong()))
        state = ProfilerSessionMachine.evaluate(waitingState, ProfilerSessionEvent.AckSucceeded).state
        reply.attempted = true
        val sent = with(arena.unwrap) { responder.sendSeccompContinue(result, response.unwrap) }
        if (sent == SeccompReplyResult.NOTIFICATION_GONE) return terminateSession()
        ledger.record(SessionEvent.ContinueReplied(System.nanoTime(), pid.toLong(), 0L))
        result.deferredShutdownReason?.let(onShutdown)
        return NotifResult.HANDLED
    }

    context(arena: NativeArena) private fun handleFailedHandshake(
        result: HandshakeSession.Failed,
        waitingState: ProfilerState,
        response: ManagedSegment,
        pid: Int,
        reply: ReplyAttempt,
    ): NotifResult {
        state = ProfilerSessionMachine.evaluate(waitingState, ProfilerSessionEvent.HandshakeFailed).state
        reply.attempted = true
        val sent = with(arena.unwrap) { responder.sendSeccompError(result, response.unwrap, ECONNRESET) }
        if (sent == SeccompReplyResult.NOTIFICATION_GONE) return terminateSession()
        ledger.record(SessionEvent.ErrorReplied(System.nanoTime(), pid.toLong(), ECONNRESET))
        return NotifResult.TERMINATE
    }

    context(arena: NativeArena) private fun handlePassedThrough(
        result: HandshakeSession.PassedThrough,
        waitingState: ProfilerState,
        response: ManagedSegment,
        pid: Int,
        reply: ReplyAttempt,
    ): NotifResult {
        state = ProfilerSessionMachine.evaluate(waitingState, ProfilerSessionEvent.PassedThrough).state
        reply.attempted = true
        val sent = with(arena.unwrap) { responder.sendSeccompContinue(result.acknowledged(), response.unwrap) }
        if (sent == SeccompReplyResult.NOTIFICATION_GONE) return terminateSession()
        ledger.record(SessionEvent.ContinueReplied(System.nanoTime(), pid.toLong(), 0L))
        return NotifResult.PASS_THROUGH
    }

    context(arena: NativeArena) private fun failTransport(
        handshake: HandshakeSession.Active,
        response: ManagedSegment,
        pid: Int,
        replyAttempted: Boolean,
    ): NotifResult {
        terminateSession()
        if (replyAttempted) return NotifResult.TERMINATE
        try {
            val reply = with(arena.unwrap) { responder.sendSeccompError(handshake.failed(), response.unwrap, ECONNRESET) }
            if (reply == SeccompReplyResult.SENT) ledger.record(SessionEvent.ErrorReplied(System.nanoTime(), pid.toLong(), ECONNRESET))
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (interrupted: java.nio.channels.ClosedByInterruptException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (expectedReplyFailure: Exception) {
            logSessionFailure("Error reply failed", expectedReplyFailure)
        }
        return NotifResult.TERMINATE
    }

    private fun terminateSession(): NotifResult {
        state = ProfilerSessionMachine.evaluate(state, ProfilerSessionEvent.TransportFailed).state
        return NotifResult.TERMINATE
    }

    private fun logSessionFailure(
        category: String,
        failure: Throwable,
    ) {
        if (failure is InterruptedException || failure is java.nio.channels.ClosedByInterruptException) Thread.currentThread().interrupt()
        // Exceptions may contain tracee-controlled paths or data. Keep only the type and bounded numeric ledger.
        ledger.record(SessionEvent.ReplyFailed(System.nanoTime(), 0, failure.javaClass.simpleName.take(MAX_SESSION_DIAGNOSTIC_TYPE_LENGTH), (failure as? ControlSocketFailure)?.errno))
        logger.severe { "$category (${failure.javaClass.simpleName.take(MAX_SESSION_DIAGNOSTIC_TYPE_LENGTH)}); recent events=${ledger.dump().takeLast(MAX_RECENT_SESSION_EVENTS).joinToString("; ")}" }
    }

    /** Materializes native notification fields into a heap-only event before publication. */
    context(arena: NativeArena) private fun resolveNotificationEvent(
        pid: Int,
        syscallNr: Int,
        args: LongArray,
    ): SyscallEvent<SyscallEventState.Resolved> {
        val tid = Tid(pid)
        val syscallName = syscallMap[syscallNr] ?: "SYSCALL_$syscallNr"
        val resolution = resolver.resolveEvidence(tid, syscallName, args)
        return SyscallEvent.fromResolution(
            tid = tid,
            syscallName = syscallName,
            args = args.toCollection(ArrayList(args.size)),
            pathResolution = resolution,
        )
    }

    private fun transitionToAckWait(
        currentState: ProfilerState.ActiveSession,
        notificationId: Long,
        event: SyscallEvent<SyscallEventState.Resolved>,
    ): ProfilerState =
        ProfilerSessionMachine
        .evaluate(currentState, ProfilerSessionEvent.NotificationReceived(notificationId, event))
        .let { ProfilerSessionMachine.evaluate(it.state, ProfilerSessionEvent.EventDelivered).state }

    /**
     * Publishes a heap event and waits for its required listener acknowledgement before the
     * tracee may receive a kernel response. This boundary must never send a seccomp reply.
     */
    context(arena: NativeArena) private fun deliverAndAwaitAck(
        pid: Int,
        event: SyscallEvent<SyscallEventState.Resolved>,
        handshake: HandshakeSession.Active,
        deadline: Deadline,
    ): HandshakeSession {
        parser.writeSocketPoll(socketPollFd, socketFd.value, NativeConstants.POLLIN)
        with(arena.unwrap) {
            publisher.sendTraceEvent(socketFd, event)
        }
        ledger.record(SessionEvent.EventSent(System.nanoTime(), pid.toLong()))
        return with(arena.unwrap) {
            handshake.performHandshake(socketFd, ioOps, socketPollFd.unwrap, ackBuf.unwrap, onShutdown, deadline)
        }
    }

    /**
     * Noise filter bypass logic helper.
     */
    private fun isNoisePath(
        nr: Int,
        resolvedEvent: SyscallEvent<SyscallEventState.Resolved>,
    ): Boolean {
        if (io.mazewall.platform.seccomp.SupervisedKind
            .classify(
                io.mazewall.platform.lifecycle
                    .SyscallNumber(nr),
                io.mazewall.platform.syscall.Arch
                .current(),
            )
            is io.mazewall.platform.seccomp.SupervisedKind.Open &&
            resolvedEvent.paths.isNotEmpty()
        ) {
            val pathStr = resolvedEvent.paths.first()
            try {
                val normalizedPathStr = PathNormalizerHelper.normalizePath(pathStr)
                val path = java.nio.file.Paths
                    .get(normalizedPathStr)
                return ProfilerNoiseClassifier.isNoise(
                    io.mazewall.platform.seccomp.SupervisedKind
                        .classify(
                            io.mazewall.platform.lifecycle
                                .SyscallNumber(nr),
                            io.mazewall.platform.syscall.Arch
                            .current(),
                        ),
                    path,
                    ProfilerBypassPaths.safePaths,
                )
            } catch (_: java.nio.file.InvalidPathException) {
                return false
            }
        }
        return false
    }

    companion object {
        private const val ECONNRESET = 104
        private val logger = java.util.logging.Logger
            .getLogger(ProfilerSessionHandler::class.java.name)
    }

    private class ReplyAttempt(
        var attempted: Boolean = false,
    )
}

/** Profiler-local, fail-closed bypass roots used only to classify JVM loader noise. */
private object ProfilerBypassPaths {
    val safePaths: List<java.nio.file.Path> = buildList {
        val candidates = buildList {
            System.getProperty("java.home")?.takeIf(String::isNotBlank)?.let { add(it) }
            System
                .getProperty("java.class.path")
                ?.split(java.io.File.pathSeparator)
                ?.filter(String::isNotBlank)
                ?.let(::addAll)
        }
        candidates.forEach { candidate ->
            runCatching {
                java.nio.file.Paths
                .get(candidate)
                .toRealPath()
            }.getOrNull()
                ?.let(::add)
        }
    }
}
