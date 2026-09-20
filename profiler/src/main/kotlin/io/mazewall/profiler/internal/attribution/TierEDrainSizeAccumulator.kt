package io.mazewall.profiler.internal.attribution

/**
 * Fixed-memory distribution of non-empty ring-drain sizes.
 *
 * Buckets are powers of two and snapshots report inclusive upper bounds. This deliberately avoids
 * retaining individual batch sizes in a long-running collector merely for benchmark telemetry.
 */
internal class TierEDrainSizeAccumulator {
    private val buckets = LongArray(Int.SIZE_BITS - 1)
    private var samples = 0L

    fun record(size: Int) {
        require(size > 0) { "a recorded drain size must be positive" }
        val bucket = Int.SIZE_BITS - Integer.numberOfLeadingZeros(size) - 1
        buckets[bucket] = Math.addExact(buckets[bucket], 1)
        samples = Math.addExact(samples, 1)
    }

    fun snapshot(): TierEDrainSizeDistribution =
        TierEDrainSizeDistribution(
            samples = samples,
            p50UpperRecords = percentileUpper(50),
            p95UpperRecords = percentileUpper(95),
            p99UpperRecords = percentileUpper(99),
        )

    private fun percentileUpper(percent: Int): Long {
        if (samples == 0L) return 0
        val rank = (samples / 100) * percent + ((samples % 100) * percent + 99) / 100
        var cumulative = 0L
        for (index in buckets.indices) {
            cumulative = Math.addExact(cumulative, buckets[index])
            if (cumulative >= rank) return (1L shl (index + 1)) - 1
        }
        error("drain-size histogram lost a recorded sample")
    }
}

internal data class TierEDrainSizeDistribution(
    val samples: Long,
    val p50UpperRecords: Long,
    val p95UpperRecords: Long,
    val p99UpperRecords: Long,
)
