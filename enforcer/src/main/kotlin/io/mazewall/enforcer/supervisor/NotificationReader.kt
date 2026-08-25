package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.recover
import io.mazewall.onSuccess
import io.mazewall.core.Deadline
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.IoctlCommand
import io.mazewall.ffi.IoctlPayload
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.ffi.typed

/**
 * Blocking notification/response IO primitives extracted from [SupervisorSessionHandler]
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
    private val engine: io.mazewall.NativeEngine,
    private val logger: java.util.logging.Logger,
) {
    /**
     * Deadline-bounded poll of [socketFd] for a JVM validation response, with interrupt-aware
     * EINTR backoff. Returns the poll revent count (<= 0 on timeout/failure).
     */
    data class AwaitResult(val revents: Long, val remainingMillis: Int)

    fun awaitJvmResponse(
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        timeoutMs: Long,
        slowThresholdMs: Long,
    ): AwaitResult {
        val arena = io.mazewall.ffi.memory.NativeArena.ofConfined()
        arena.use { _ ->
            val pollFd = PollFdSegment.of(arena.allocate(Layouts.POLLFD))
            pollFd.setFd(socketFd.value)
            pollFd.setEvents(NativeConstants.POLLIN)

            val startMs = System.currentTimeMillis()
            val deadline = Deadline.afterMillis(timeoutMs)
            var count = 0L
            val pollFdManaged = pollFd.managed
            var eintrCount = 0
            while (deadline.remainingMillis() > 0) {
                if (Thread.currentThread().isInterrupted) {
                    logger.warning("[SUPERVISOR-DIAGNOSTIC] JVM validation poll interrupted.")
                    break
                }

                val pollRes = engine.raw.poll(pollFdManaged, 1L, deadline.remainingMillis())

                var gotEintr = false
                count = pollRes.recover { errno, _ ->
                    if (errno == NativeConstants.EINTR) {
                        gotEintr = true
                        0L
                    } else {
                        0L
                    }
                }
                if (pollRes is LinuxNative.SyscallResult.Success) {
                    count = pollRes.value
                    break
                }
                if (!gotEintr) {
                    break
                }

                eintrCount++
                if (eintrCount > 1) {
                    if (eintrCount > 3) {
                        try {
                            Thread.sleep(1)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    } else {
                        Thread.yield()
                    }
                }
            }
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

    /**
     * Single SECCOMP_IOCTL_NOTIF_RECV with an unconditional EINTR retry loop. The caller's outer
     * poll loop provides the shutdown path (POLLHUP/POLLIN-on-socket → LoopAction.Shutdown), so
     * EINTR here only re-blocks, matching the previous behavior verbatim.
     */
    fun recvNotification(listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>, notif: ManagedSegment): Boolean {
        var recvRes: LinuxNative.SyscallResult<Long, *>
        while (true) {
            recvRes = engine.raw.ioctl(
                listenerFd,
                IoctlCommand.SECCOMP_IOCTL_NOTIF_RECV,
                notif.typed<IoctlPayload.SeccompNotif>(),
            )
            if (recvRes is LinuxNative.SyscallResult.Error<*> && recvRes.errno == NativeConstants.EINTR) {
                continue
            }
            break
        }
        var ok = false
        recvRes.onSuccess { ok = true }
        return ok
    }
}
