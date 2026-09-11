package io.mazewall.platform.seccomp.daemon

import io.mazewall.LinuxNative
import io.mazewall.NativeEngine
import io.mazewall.core.Deadline
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.NativeCloseFailure
import io.mazewall.core.RealSocketManager
import io.mazewall.core.SocketManager
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.ffi.memory.SeccompSessionBuffers
import io.mazewall.ffi.memory.fill
import io.mazewall.ffi.memory.readByte
import io.mazewall.ffi.memory.readLong
import io.mazewall.platform.seccomp.UserNotifReply
import io.mazewall.platform.seccomp.UserNotifReplyResult

/**
 * Unified reactor handler for active seccomp user notifications and UNIX domain control sockets.
 * Owns its buffer leases and pending notification work. The enclosing connection retains
 * listener/socket ownership; this handler only closes the control socket through the supplied
 * ownership callback on terminal failure. Close drains work and releases buffers idempotently;
 * a reported pre-retirement failure requires retaining this handler for cleanup retry.
 */
public class SeccompSessionHandler(
    private val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    private val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
    private val notifHandler: SeccompNotifHandler,
    private val onShutdown: (String) -> Unit = {},
    private val onSocketClosed: () -> Unit = {},
    private val engine: NativeEngine = LinuxNative,
    private val socketManager: SocketManager = RealSocketManager,
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {
    private val buffers: SeccompSessionBuffers

    init {
        configureListenerNonblocking()
        buffers = SeccompSessionBuffers.acquire()
    }

    private val ackBuf: ManagedSegment get() = buffers.ack
    private val notif: ManagedSegment get() = buffers.notifLease.segment
    private val resp: ManagedSegment get() = buffers.respLease.segment

    private var isPassThrough = false
    private var pending: PendingNotification? = null

    /** Pending work must advance on idle polls and EINTR, not only descriptor readiness. */
    public fun pollTimeoutMillis(): Int = pending?.let { minOf(WORK_POLL_MS, it.deadline.remainingMillis(nanoTime())) } ?: IDLE_POLL_MS

    private class PendingNotification(
        val id: Long,
        val deadline: Deadline,
        val startedAtNanos: Long,
        val lease: NotificationReplyLease = NotificationReplyLease(),
        var work: NotificationWork? = null,
    ) {
        var deadlineNearObserved: Boolean = false
        var finished: Boolean = false
    }

    public var isTerminated: Boolean = false
        private set

    private var notifReleased = false
    private var respReleased = false
    private var arenaClosed = false

    override fun close() {
        when (val outcome = closeWithResult()) {
            SeccompCleanupOutcome.Complete -> Unit
            is SeccompCleanupOutcome.Incomplete -> {
                val failure = IllegalStateException("Incomplete seccomp session cleanup")
                outcome.failures.forEach(failure::addSuppressed)
                throw failure
            }
        }
    }

    @io.mazewall.MazewallInternal
    @Synchronized
    public fun closeWithResult(): SeccompCleanupOutcome = closeWithResult { }

    @Synchronized
    internal fun closeWithResult(recordOutcome: (SeccompCleanupOutcome) -> Unit): SeccompCleanupOutcome =
        SeccompCleanupInterpreter.closeResources(
        listOf(
        {
            isTerminated = true
            val current = pending
            if (current != null) denyAndTerminate(current)
        },
        {
            if (!notifReleased) {
                buffers.notifLease.close()
                notifReleased = true
            }
        },
        {
            if (!respReleased) {
                buffers.respLease.close()
                respReleased = true
            }
        },
        {
            if (!arenaClosed) {
                buffers.arena.close()
                arenaClosed = true
            }
        },
        ),
        recordOutcome,
    )

    context(arena: NativeArena) public fun handleActiveListener(pollFds: ManagedSegment): LoopAction {
        NotificationLivenessMetrics.counters.reactorPoll()
        if (isTerminated) {
            return LoopAction.Break
        }

        if (pending != null) return advancePending(pollFds)

        // pollFds layout:
        // [0]: listenerFd (Layouts.POLLFD_SIZE)
        // [1]: socketFd   (Layouts.POLLFD_SIZE)
        val errorOrHup = NativeConstants.POLLERR.toInt() or NativeConstants.POLLHUP.toInt() or NativeConstants.POLLNVAL.toInt()

        // Process pending seccomp notifications BEFORE checking for shutdown.
        // This ensures a blocked tracee always gets a response even if a shutdown
        // command arrives on the control socket at the same time.
        val pfd1 = PollFdSegment.of(pollFds.asSlice(0L, Layouts.POLLFD_SIZE))
        val listenerRevents = pfd1.getRevents().toInt()
        if ((listenerRevents and errorOrHup) != 0) {
            isTerminated = true
            return LoopAction.Break
        }

        if ((listenerRevents and NativeConstants.POLLIN.toInt()) != 0) {
            notif.fill(0)
            when (UserNotifReceive.receiveOne(engine.raw, listenerFd, notif)) {
                ReceiveOutcome.Received -> Unit
                ReceiveOutcome.RetryAfterPoll,
                ReceiveOutcome.NotReady,
                -> return LoopAction.Continue
                ReceiveOutcome.Closed,
                is ReceiveOutcome.Failed,
                -> {
                    isTerminated = true
                    return LoopAction.Break
                }
            }

            val startedAtNanos = nanoTime()
            val current = PendingNotification(
                notif.readLong(0L),
                Deadline.afterMillis(NOTIFICATION_TIMEOUT_MS, startedAtNanos),
                startedAtNanos,
            )
            pending = current
            NotificationLivenessMetrics.counters.started()
            // Queued tracees must not turn a pending helper wait into a busy listener poll.
            pfd1.setFd(-1)
            try {
                if (isPassThrough) {
                    current.work = object : NotificationWork {
                        context(arena: NativeArena) override fun poll(): NotificationProgress =
                            NotificationProgress.Ready({
                            UserNotifReply.encodeContinue(resp, current.id)
                            UserNotifReply.send(engine.raw, listenerFd, resp)
                        })
                    }
                } else {
                    current.work = notifHandler.beginNotification(notif, resp, listenerFd, socketFd, current.deadline)
                }
            } catch (expectedNotificationFailure: Throwable) {
                terminateAfterFailure(current, expectedNotificationFailure)
            }
            return advancePending(pollFds)
        }

        return handleControlSocket(pollFds)
    }

    private fun handleControlSocket(pollFds: ManagedSegment): LoopAction {
        val errorOrHup = NativeConstants.POLLERR.toInt() or NativeConstants.POLLHUP.toInt() or NativeConstants.POLLNVAL.toInt()
        // Only check for shutdown after any pending notification has been handled.
        val pfd2 = PollFdSegment.of(pollFds.asSlice(Layouts.POLLFD_SIZE, Layouts.POLLFD_SIZE))
        val socketRevents = pfd2.getRevents().toInt()
        if (!isPassThrough && (socketRevents and (NativeConstants.POLLIN.toInt() or errorOrHup)) != 0) {
            val isDeadOrShutdown = (socketRevents and errorOrHup) != 0 || handleShutdownRequest(ackBuf, pollFds)
            if (isDeadOrShutdown) {
                isTerminated = true
                return LoopAction.Break
            }
        }

        return LoopAction.Continue
    }

    // SUPPRESSION-RATIONALE: Terminal claim, timeout and Await paths must return independently so a notification cannot reach a second reply branch.
    context(arena: NativeArena) private fun advancePending(pollFds: ManagedSegment): LoopAction {
        val current = checkNotNull(pending)
        observePending(current)
        if (nanoTime() - current.deadline.nanoTime >= 0) return denyAndTerminate(current, expired = true)
        val progress = try {
            checkNotNull(current.work).poll()
        } catch (expectedNotificationFailure: Throwable) {
            terminateAfterFailure(current, expectedNotificationFailure)
        }
        // Local preparation may consume the last deadline tick. A late completion never wins.
        if (nanoTime() - current.deadline.nanoTime >= 0) {
            return denyAndTerminate(current, expired = true, lateCompletion = progress is NotificationProgress.Ready)
        }
        return when (progress) {
            NotificationProgress.Await -> LoopAction.Continue
            is NotificationProgress.Ready -> {
                if (!current.lease.claim()) return LoopAction.Break
                var primaryFailure: Throwable? = null
                try {
                    val outcome = recordReply(current, progress.reply)
                    if (outcome != UserNotifReplyResult.Sent) isTerminated = true
                    progress.onReply(outcome)
                    when (progress.result) {
                        NotifResult.HANDLED -> Unit
                        NotifResult.TERMINATE -> isTerminated = true
                        NotifResult.PASS_THROUGH -> if (!isTerminated) {
                            isPassThrough = true
                            PollFdSegment.of(pollFds.asSlice(Layouts.POLLFD_SIZE, Layouts.POLLFD_SIZE)).setFd(-1)
                            closeControlSocket()
                        }
                    }
                } catch (expectedReplyFailure: Throwable) {
                    isTerminated = true
                    primaryFailure = expectedReplyFailure
                } finally {
                    pending = null
                    PollFdSegment.of(pollFds.asSlice(0L, Layouts.POLLFD_SIZE)).setFd(listenerFd.value)
                    finish(current)
                    val cleanup = CleanupCollector(primaryFailure)
                    cleanup.attempt { current.work?.close() }
                    cleanup.throwIfFailed()
                }
                if (isTerminated) LoopAction.Break else handleControlSocket(pollFds)
            }
        }
    }

    private fun denyAndTerminate(
        current: PendingNotification,
        expired: Boolean = false,
        lateCompletion: Boolean = false,
    ): LoopAction {
        isTerminated = true
        pending = null
        var primaryFailure: Throwable? = null
        try {
            if (current.lease.claim()) {
                if (expired) NotificationLivenessMetrics.counters.expired(lateCompletion)
                UserNotifReply.encodeError(resp, current.id, NativeConstants.EPERM)
                // Deadline exhaustion permits one best-effort terminal attempt, no retry loop.
                recordReply(current) { UserNotifReply.send(engine.raw, listenerFd, resp, maxAttempts = 1) }
            }
        } catch (expectedReplyFailure: Throwable) {
            primaryFailure = expectedReplyFailure
        } finally {
            finish(current)
            val cleanup = CleanupCollector(primaryFailure)
            cleanup.attempt { current.work?.close() }
            cleanup.throwIfFailed()
        }
        return LoopAction.Break
    }

    private fun recordReply(
        current: PendingNotification,
        reply: () -> UserNotifReplyResult,
    ): UserNotifReplyResult {
        val outcome = try {
            reply()
        } catch (expectedReplyFailure: Throwable) {
            NotificationLivenessMetrics.counters.replyThrew()
            throw expectedReplyFailure
        } finally {
            NotificationLivenessMetrics.counters.replyLatency(nanoTime() - current.startedAtNanos)
        }
        NotificationLivenessMetrics.counters.reply(outcome)
        return outcome
    }

    private fun terminateAfterFailure(
        current: PendingNotification,
        failure: Throwable,
    ): Nothing {
        try {
            denyAndTerminate(current)
        } catch (expectedCleanupFailure: Throwable) {
            if (expectedCleanupFailure !== failure) failure.addSuppressed(expectedCleanupFailure)
        }
        throw failure
    }

    private fun observePending(current: PendingNotification) {
        val now = nanoTime()
        val deadlineNear = !current.deadlineNearObserved && current.deadline.remainingMillis(now) <= DEADLINE_NEAR_MILLIS
        if (deadlineNear) current.deadlineNearObserved = true
        NotificationLivenessMetrics.counters.observePending(now - current.startedAtNanos, deadlineNear)
    }

    private fun finish(current: PendingNotification) {
        if (current.finished) return
        current.finished = true
        NotificationLivenessMetrics.counters.finished()
    }

    private fun configureListenerNonblocking() {
        val flags = engine.raw.fcntl(listenerFd, NativeConstants.F_GETFL, 0)
        val current = when (flags) {
            is LinuxNative.SyscallResult.Success -> flags.value
            is LinuxNative.SyscallResult.Error -> throw IllegalStateException(
                "Unable to read seccomp listener flags: errno=${flags.errno}",
            )
        }
        when (val configured = engine.raw.fcntl(listenerFd, NativeConstants.F_SETFL, current or NativeConstants.O_NONBLOCK)) {
            is LinuxNative.SyscallResult.Success -> Unit
            is LinuxNative.SyscallResult.Error -> throw IllegalStateException(
                "Unable to make seccomp listener nonblocking: errno=${configured.errno}",
            )
        }
    }

    private fun handleShutdownRequest(
        ackBuf: ManagedSegment,
        pollFds: ManagedSegment,
    ): Boolean {
        var res: LinuxNative.SyscallResult<Long, *>
        var attempts = 0
        do {
            attempts++
            res = engine.memory.read(socketFd, ackBuf, 1L)
        } while (res is LinuxNative.SyscallResult.Error<*> && res.errno == NativeConstants.EINTR && attempts < MAX_ATTEMPTS)
        return when (res) {
            is LinuxNative.SyscallResult.Success -> {
                val value = res.value
                if (value > 0) {
                    val command = ackBuf.readByte(0L)
                    if (command == SHUTDOWN_COMMAND_BYTE) {
                        onShutdown("Parent Command")
                        true
                    } else if (command == PASS_THROUGH_COMMAND_BYTE) {
                        isPassThrough = true
                        // Disable polling on socketFd by setting its fd to -1
                        val pfd2 = PollFdSegment.of(pollFds.asSlice(Layouts.POLLFD_SIZE, Layouts.POLLFD_SIZE))
                        pfd2.setFd(-1)
                        closeControlSocket()
                        false
                    } else {
                        false
                    }
                } else {
                    !isPassThrough
                }
            }
            is LinuxNative.SyscallResult.Error -> res.errno != NativeConstants.EAGAIN
        }
    }

    private fun closeControlSocket() {
        try {
            socketManager.close(socketFd)
        } catch (failure: NativeCloseFailure) {
            onSocketClosed()
            throw failure
        }
        onSocketClosed()
    }

    public companion object {
        private const val NOTIFICATION_TIMEOUT_MS = 30_000L
        private const val MAX_ATTEMPTS = 8
        private const val WORK_POLL_MS = 10
        private const val DEADLINE_NEAR_MILLIS = 5_000
        private const val IDLE_POLL_MS = 1000
        private const val SHUTDOWN_COMMAND_BYTE: Byte = 0x53.toByte()
        private const val PASS_THROUGH_COMMAND_BYTE: Byte = 0x54.toByte()
    }
}

/** Collect one primary operation failure and suppress all later cleanup failures onto it. */
private class CleanupCollector(
    primary: Throwable? = null,
) {
    private var failure: Throwable? = primary

    @Suppress("TooGenericExceptionCaught")
    fun attempt(action: () -> Unit) {
        try {
            action()
        } catch (cleanupFailure: Throwable) {
            val current = failure
            if (current == null) {
                failure = cleanupFailure
            } else if (current !== cleanupFailure && current.suppressed.none { it === cleanupFailure }) {
                current.addSuppressed(cleanupFailure)
            }
        }
    }

    fun throwIfFailed() {
        failure?.let { throw it }
    }
}
