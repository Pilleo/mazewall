package io.mazewall.profiler.internal.attribution

/** Bounded 100-microsecond discovery histogram through 500 ms, with overflow. */
internal class CoverageDiscoveryLatency {
    private val counts = LongArray(5_002)
    private var samples = 0L

    fun record(nanos: Long) {
        require(nanos >= 0)
        val bucket = minOf(nanos / 100_000L, 5_001L).toInt()
        counts[bucket]++
        samples++
    }

    fun sampleCount(): Long = samples

    fun p99UpperNanos(): Long {
        if (samples == 0L) return 0
        val rank = ((samples * 99L) + 99L) / 100L
        var cumulative = 0L
        for (index in counts.indices) {
            cumulative += counts[index]
            if (cumulative >= rank) return if (index >= 5_001) Long.MAX_VALUE else (index + 1L) * 100_000L - 1L
        }
        return Long.MAX_VALUE
    }
}
