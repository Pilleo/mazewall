package io.mazewall.enforcer.internal.supervisor

import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.Deadline
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.seccomp.daemon.ReceiveOutcome
import io.mazewall.platform.seccomp.daemon.UserNotifReceive

/**
 * Deadline-bounded notification/response IO primitives extracted from [SupervisorSessionHandler]
 * (issue-20260823-171956, slice 4).
 *
 * Encapsulates the EINTR-tolerant poll/recv loops and their backoff/interrupt handling.
 * Decision logic (verdicts, routing, shutdown) stays in the handler.
 *
 * Invariants preserved verbatim:
 * - Interrupt checks at the top of every poll iteration; InterruptedException re-interrupts and
 *   breaks out of deadline-bounded loops.
 * - EINTR retries never mask shutdown: the caller's deadline bounds all waiting.
 */
internal class NotificationReader(
    private val engine: NativeEngine,
    private val logger: java.util.logging.Logger,
) {
    private companion object {
        const val SINGLE_POLL_FD: Long = 1L
        const val IMMEDIATE_EINTR_RETRY_LIMIT = 1
        const val YIELD_EINTR_RETRY_LIMIT = 3
        const val EINTR_SLEEP_MILLIS = 1L
    }

    /**
     * Deadline-bounded poll of [socketFd] for a JVM validation response, with interrupt-aware
     * EINTR backoff. Returns the poll revent count (<= 0 on timeout/failure).
     */
    data class AwaitResult(
        val revents: Long,
        val remainingMillis: Int,
    )

    fun awaitJvmResponse(
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        timeoutMs: Long,
        slowThresholdMs: Long,
    ): AwaitResult {
        val arena = io.mazewall.ffi.memory.NativeArena
            .ofConfined()
        arena.use { _ ->
            val pollFd = PollFdSegment.of(arena.allocate(Layouts.POLLFD))
            pollFd.setFd(socketFd.value)
            pollFd.setEvents(NativeConstants.POLLIN)

            val startMs = System.currentTimeMillis()
            val deadline = Deadline.afterMillis(timeoutMs)
            val pollFdManaged = pollFd.managed
            val count = pollUntilResponse(pollFdManaged, deadline)
            val durationMs = System.currentTimeMillis() - startMs
            if (durationMs > slowThresholdMs) {
                logger.warning(
                    "[SUPERVISOR-DIAGNOSTIC] JVM validation poll took ${durationMs}ms " +
                        "(threshold=${slowThresholdMs}ms). Possible deadlock or slow stack trace resolution.",
                )
            }
            return AwaitResult(count, deadline.remainingMillis())
        }
    }

    private fun pollUntilResponse(
        pollFd: ManagedSegment,
        deadline: Deadline,
    ): Long {
        var eintrCount = 0
        while (deadline.remainingMillis() > 0) {
            if (Thread.currentThread().isInterrupted) {
                logger.warning("[SUPERVISOR-DIAGNOSTIC] JVM validation poll interrupted.")
                return 0L
            }
            when (val result = engine.raw.poll(pollFd, SINGLE_POLL_FD, deadline.remainingMillis())) {
                is LinuxNative.SyscallResult.Success -> return result.value
                is LinuxNative.SyscallResult.Error -> {
                    if (result.errno != NativeConstants.EINTR) return 0L
                    eintrCount++
                    if (!backoffAfterEintr(eintrCount)) return 0L
                }
            }
        }
        return 0L
    }

    private fun backoffAfterEintr(eintrCount: Int): Boolean =
        when {
            eintrCount <= IMMEDIATE_EINTR_RETRY_LIMIT -> true
            eintrCount <= YIELD_EINTR_RETRY_LIMIT -> {
                Thread.yield()
                true
            }
            else ->
                try {
                    Thread.sleep(EINTR_SLEEP_MILLIS)
                    true
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    false
                }
        }

    /**
     * Performs exactly one nonblocking receive attempt. EINTR and EAGAIN return control to the
     * reactor so it must observe readiness and its deadline again before another ioctl.
     */
    fun recvNotification(
        listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
        notif: ManagedSegment,
    ): ReceiveOutcome = UserNotifReceive.receiveOne(engine.raw, listenerFd, notif)
}
