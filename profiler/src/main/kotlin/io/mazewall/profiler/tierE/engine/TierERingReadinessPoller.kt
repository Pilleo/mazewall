package io.mazewall.profiler.tierE.engine

import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.platform.native.LinuxNative.SyscallResult
import io.mazewall.platform.native.RawSyscallOperations
import io.mazewall.profiler.internal.attribution.TierEEventReadiness
import java.lang.foreign.Arena
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/** EINTR-safe readiness wait for a pollable BPF ring-buffer map descriptor. */
internal class TierERingReadinessPoller(
    private val native: RawSyscallOperations,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    fun await(
        ringFd: Int,
        timeoutMillis: Int,
    ): TierEEventReadiness {
        require(timeoutMillis in 0..MAX_TIMEOUT_MILLIS) {
            "Tier E readiness timeout must be between zero and $MAX_TIMEOUT_MILLIS milliseconds"
        }
        val deadline = Math.addExact(nanoTime(), TimeUnit.MILLISECONDS.toNanos(timeoutMillis.toLong()))
        Arena.ofConfined().use { arena ->
            val pollFd = with(arena) { PollFdSegment.allocate() }
            pollFd.setFd(ringFd)
            pollFd.setEvents(NativeConstants.POLLIN)
            while (true) {
                pollFd.setRevents(0)
                val remaining = remainingMillis(deadline)
                when (val result = native.poll(pollFd.managed, 1L, remaining)) {
                    is SyscallResult.Success -> {
                        if (result.value == 0L) return TierEEventReadiness.TIMEOUT
                        val revents = pollFd.getRevents().toInt()
                        val failures = NativeConstants.POLLERR.toInt() or
                            NativeConstants.POLLHUP.toInt() or NativeConstants.POLLNVAL.toInt()
                        check(revents and failures == 0) {
                            "Tier E ring readiness failed with poll revents=0x${revents.toString(16)}"
                        }
                        check(revents and NativeConstants.POLLIN.toInt() != 0) {
                            "Tier E ring readiness returned unsupported poll revents=0x${revents.toString(16)}"
                        }
                        return TierEEventReadiness.READY
                    }
                    is SyscallResult.Error -> {
                        if (result.errno != NativeConstants.EINTR) result.throwErrno("poll(Tier E ring buffer)")
                        if (nanoTime() >= deadline) return TierEEventReadiness.TIMEOUT
                    }
                }
            }
        }
    }

    private fun remainingMillis(deadline: Long): Int {
        val remainingNanos = (deadline - nanoTime()).coerceAtLeast(0L)
        return ceil(remainingNanos / NANOS_PER_MILLI.toDouble()).toInt()
    }

    private companion object {
        const val MAX_TIMEOUT_MILLIS = 10_000
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
