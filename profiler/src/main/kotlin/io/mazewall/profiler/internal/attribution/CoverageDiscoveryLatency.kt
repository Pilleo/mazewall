package io.mazewall.profiler.internal.attribution

/** Bounded 100-microsecond discovery histogram through 500 ms, with overflow. */
internal class CoverageDiscoveryLatency {
    private val counts = LongArray(OVERFLOW_BUCKET + 1)
    private var samples = 0L

    fun record(nanos: Long) {
        require(nanos >= 0)
        val bucket = minOf(nanos / BUCKET_WIDTH_NANOS, OVERFLOW_BUCKET.toLong()).toInt()
        counts[bucket]++
        samples++
    }

    fun sampleCount(): Long = samples

    fun p99UpperNanos(): Long {
        if (samples == 0L) return 0
        val rank = ((samples * P99_PERCENTILE) + (PERCENT - 1)) / PERCENT
        var cumulative = 0L
        for (index in counts.indices) {
            cumulative += counts[index]
            if (cumulative >= rank) return if (index >= OVERFLOW_BUCKET) Long.MAX_VALUE else (index + 1L) * BUCKET_WIDTH_NANOS - 1L
        }
        return Long.MAX_VALUE
    }

    private companion object {
        const val BUCKET_WIDTH_NANOS = 100_000L
        const val P99_PERCENTILE = 99L
        const val PERCENT = 100L
        const val OVERFLOW_BUCKET = 5_001 // floor(500 ms / 100 us) + 1
    }
}
