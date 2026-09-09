package io.mazewall.profiler.engine

import io.mazewall.LinuxNative
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.Tid
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
import io.mazewall.map
import io.mazewall.onSuccess
import io.mazewall.platform.seccomp.daemon.LoopAction
import io.mazewall.platform.seccomp.daemon.NotifResult
import io.mazewall.platform.seccomp.daemon.SeccompNotifHandler
import io.mazewall.profiler.ffi.HandshakeSession
import io.mazewall.profiler.ffi.NativeIoOperations
import io.mazewall.profiler.ffi.SeccompReplyResult
import io.mazewall.profiler.ffi.SeccompResponder
import io.mazewall.profiler.ffi.TraceEventPublisher
import io.mazewall.recover
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
     */
    context(arena: NativeArena) override fun processNotification(
        notif: ManagedSegment,
        resp: ManagedSegment,
        listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    ): NotifResult {
        val currentState = state as? ProfilerState.ActiveSession ?: return NotifResult.TERMINATE

        val parsedNotif = parser.readNotif(notif)
        val id = parsedNotif.id
        val pidVal = parsedNotif.pid
        val nr = parsedNotif.nr

        System.err.println("[DAEMON-DEBUG] Received notification: id=$id, pid=$pidVal, nr=$nr")
        val handshake = HandshakeSession.Active(id, listenerFd)

        var replyAttempted = false

        try {
            ledger.record(SessionEvent.Notified(System.nanoTime(), pidVal.toLong(), nr.toLong()))

            val resolvedEvent = resolveNotificationEvent(pidVal, nr, parsedNotif.args)

            // Optimisation: skip event delivery for JVM-internal paths that generate noise
            // (JDK home, classpath, /proc, /sys).
            if (isNoisePath(nr, resolvedEvent)) {
                replyAttempted = true
                val reply = with(arena.unwrap) { responder.sendSeccompContinue(handshake.acknowledged(), resp.unwrap) }
                if (reply == SeccompReplyResult.NOTIFICATION_GONE) return terminateSession()
                return NotifResult.HANDLED
            }

            val waitingState = transitionToAckWait(currentState, id, resolvedEvent)
            state = waitingState

            val result = deliverAndAwaitAck(pidVal, resolvedEvent, handshake)
            return when (result) {
                is HandshakeSession.Success -> {
                    ledger.record(SessionEvent.AckReceived(System.nanoTime(), pidVal.toLong()))
                    state = ProfilerSessionMachine
                        .evaluate(
                        waitingState,
                        ProfilerSessionEvent.AckSucceeded,
                    ).state
                    replyAttempted = true
                    val reply = with(arena.unwrap) {
                        responder.sendSeccompContinue(result, resp.unwrap)
                    }
                    if (reply == SeccompReplyResult.NOTIFICATION_GONE) return terminateSession()
                    ledger.record(SessionEvent.ContinueReplied(System.nanoTime(), pidVal.toLong(), 0L))
                    result.deferredShutdownReason?.let(onShutdown)
                    NotifResult.HANDLED
                }
                is HandshakeSession.Failed -> {
                    System.err.println("[DAEMON-WARN] Handshake failed or shutdown triggered")
                    state = ProfilerSessionMachine
                        .evaluate(
                        waitingState,
                        ProfilerSessionEvent.HandshakeFailed,
                    ).state
                    replyAttempted = true
                    val reply = with(arena.unwrap) {
                        responder.sendSeccompError(result, resp.unwrap, ECONNRESET)
                    }
                    if (reply == SeccompReplyResult.NOTIFICATION_GONE) return terminateSession()
                    ledger.record(SessionEvent.ErrorReplied(System.nanoTime(), pidVal.toLong(), ECONNRESET))
                    NotifResult.TERMINATE
                }
                is HandshakeSession.PassedThrough -> {
                    System.err.println("[DAEMON-DEBUG] Handshake returned PassThrough")
                    state = ProfilerSessionMachine
                        .evaluate(
                        waitingState,
                        ProfilerSessionEvent.PassedThrough,
                    ).state
                    replyAttempted = true
                    val reply = with(arena.unwrap) {
                        responder.sendSeccompContinue(result.acknowledged(), resp.unwrap)
                    }
                    if (reply == SeccompReplyResult.NOTIFICATION_GONE) return terminateSession()
                    ledger.record(SessionEvent.ContinueReplied(System.nanoTime(), pidVal.toLong(), 0L))
                    NotifResult.PASS_THROUGH
                }
                is HandshakeSession.Active -> {
                    state = ProfilerSessionMachine
                        .evaluate(
                        waitingState,
                        ProfilerSessionEvent.HandshakeFailed,
                    ).state
                    NotifResult.TERMINATE
                }
            }
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
            return failTransport(handshake, resp, pidVal, replyAttempted)
        } catch (expectedStructuralFailure: Exception) {
            terminateSession()
            logSessionFailure("Structural or unrecoverable error", expectedStructuralFailure)
            if (replyAttempted) return NotifResult.TERMINATE
            throw expectedStructuralFailure
        } catch (expectedStructuralError: Error) {
            terminateSession()
            logSessionFailure("Structural or unrecoverable error", expectedStructuralError)
            throw expectedStructuralError
        }
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
        logger.severe { "$category in processNotification: ${failure.message}. Dumping SessionEventLedger:\n${ledger.dump().joinToString("\n")}" }
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
    ): HandshakeSession {
        parser.writeSocketPoll(socketPollFd, socketFd.value, NativeConstants.POLLIN)
        System.err.println("[DAEMON-DEBUG] Sending event to JVM listener: tid=$pid, syscall=${event.syscallName}, paths=${event.paths}")
        with(arena.unwrap) {
            publisher.sendTraceEvent(socketFd, event)
        }
        System.err.println("[DAEMON-DEBUG] Event sent to JVM listener.")
        ledger.record(SessionEvent.EventSent(System.nanoTime(), pid.toLong()))
        return with(arena.unwrap) {
            handshake.performHandshake(socketFd, ioOps, socketPollFd.unwrap, ackBuf.unwrap, onShutdown)
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
                io.mazewall.core.SyscallNumber(nr),
                io.mazewall.core.Arch
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
                            io.mazewall.core.SyscallNumber(nr),
                            io.mazewall.core.Arch
                            .current(),
                        ),
                    path,
                    io.mazewall.enforcer.supervisor.BypassPaths
                        .resolveForPolicy(path),
                    io.mazewall.enforcer.supervisor.BypassPaths.safeBypassPaths,
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
}
