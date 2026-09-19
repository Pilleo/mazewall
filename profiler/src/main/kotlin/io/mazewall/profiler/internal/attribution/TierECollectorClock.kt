package io.mazewall.profiler.internal.attribution

import io.mazewall.ffi.internal.RealNativeEngine
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.readLong
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.RawSyscallOperations
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg

/** A collector receipt clock in the same Linux monotonic domain as `bpf_ktime_get_ns`. */
internal fun interface TierECollectorClock {
    fun nowNanos(): Long
}

/**
 * Benchmark-only `clock_gettime(CLOCK_MONOTONIC)` reader.
 *
 * The shared 16-byte `timespec` buffer is owned by this clock and is never exposed. The collector
 * loop serializes polling, and this class also synchronizes its syscall/read pair so the buffer
 * cannot be reused concurrently. Failure is explicit: a latency benchmark must not silently
 * degrade to an unrelated Java clock domain.
 */
internal class TierELinuxMonotonicClock(
    private val raw: RawSyscallOperations = RealNativeEngine.raw,
    private val syscallNumber: Long = Arch.current().clock_gettime.toLong(),
) : TierECollectorClock,
    AutoCloseable {
    private val arena = NativeArena.ofShared()
    private val timespec = arena.allocate(TIMESPEC_BYTES)

    @Synchronized
    override fun nowNanos(): Long {
        check(arena.isAlive) { "Tier E monotonic clock is closed" }
        when (
            val result = raw.syscall(
            syscallNumber,
            NativeArg.IntArg(CLOCK_MONOTONIC),
            NativeArg.MemoryArg(timespec),
        )
        ) {
            is LinuxNative.SyscallResult.Success -> Unit
            is LinuxNative.SyscallResult.Error -> result.throwErrno("clock_gettime(CLOCK_MONOTONIC)")
        }
        val seconds = timespec.readLong(SECONDS_OFFSET)
        val nanos = timespec.readLong(NANOSECONDS_OFFSET)
        check(seconds >= 0 && nanos in 0 until NANOS_PER_SECOND) { "kernel returned an invalid CLOCK_MONOTONIC timespec" }
        return Math.addExact(Math.multiplyExact(seconds, NANOS_PER_SECOND), nanos)
    }

    @Synchronized
    override fun close() {
        arena.close()
    }

    private companion object {
        private const val CLOCK_MONOTONIC = 1
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val TIMESPEC_BYTES = 16L
        private const val SECONDS_OFFSET = 0L
        private const val NANOSECONDS_OFFSET = 8L
    }
}

/** Bounded logarithmic receipt-age summary; percentile values are inclusive upper bounds. */
internal class TierEReceiptAgeAccumulator {
    private val buckets = LongArray(BUCKET_COUNT)
    private var samples = 0L
    private var totalNanos = 0L
    private var minNanos = Long.MAX_VALUE
    private var maxNanos = 0L

    fun recordAll(agesNanos: LongArray) {
        agesNanos.forEach(::record)
    }

    fun snapshot(): TierEReceiptAgeMetrics =
        if (samples == 0L) {
            TierEReceiptAgeMetrics.EMPTY
        } else {
            TierEReceiptAgeMetrics(
                samples = samples,
                minNanos = minNanos,
                meanNanos = totalNanos / samples,
                maxNanos = maxNanos,
                p50UpperBoundNanos = percentileUpperBound(50),
                p95UpperBoundNanos = percentileUpperBound(95),
                p99UpperBoundNanos = percentileUpperBound(99),
            )
        }

    private fun record(ageNanos: Long) {
        require(ageNanos >= 0) { "receipt age must be non-negative" }
        samples = Math.addExact(samples, 1)
        totalNanos = Math.addExact(totalNanos, ageNanos)
        minNanos = minOf(minNanos, ageNanos)
        maxNanos = maxOf(maxNanos, ageNanos)
        buckets[bucketFor(ageNanos)] = Math.addExact(buckets[bucketFor(ageNanos)], 1)
    }

    private fun percentileUpperBound(percentile: Int): Long {
        val rank = ((samples * percentile) + 99) / 100
        var cumulative = 0L
        buckets.forEachIndexed { index, count ->
            cumulative += count
            if (cumulative >= rank) return bucketUpperBound(index)
        }
        return maxNanos
    }

    private fun bucketFor(ageNanos: Long): Int = if (ageNanos == 0L) 0 else Long.SIZE_BITS - ageNanos.countLeadingZeroBits()

    private fun bucketUpperBound(index: Int): Long =
        if (index == 0) {
            0
        } else if (index >= Long.SIZE_BITS - 1) {
            Long.MAX_VALUE
        } else {
            (1L shl index) - 1
        }

    private companion object {
        private const val BUCKET_COUNT = Long.SIZE_BITS
    }
}

internal data class TierEReceiptAgeMetrics(
    val samples: Long,
    val minNanos: Long,
    val meanNanos: Long,
    val maxNanos: Long,
    val p50UpperBoundNanos: Long,
    val p95UpperBoundNanos: Long,
    val p99UpperBoundNanos: Long,
) {
    internal companion object {
        val EMPTY = TierEReceiptAgeMetrics(0, 0, 0, 0, 0, 0, 0)
    }
}
