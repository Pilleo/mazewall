package io.mazewall.profiler.attribution

/** Phase-level measurements emitted by the Tier E benchmark harness. */
public data class TierEPerformanceMeasurement(
    public val mode: String,
    public val warmedRuns: Long,
    public val getStackTraceNanos: Long,
    public val rawKeyValidationNanos: Long,
    public val metadataResolutionNanos: Long,
    public val allocationBytes: Long,
    public val peakRetainedBytes: Long,
    public val tailLatencyNanos: Long,
    public val logicalStackDigest: String,
) {
    init {
        require(warmedRuns > 0) { "warmedRuns must be positive" }
        require(getStackTraceNanos >= 0 && rawKeyValidationNanos >= 0 && metadataResolutionNanos >= 0) {
            "phase timings must not be negative"
        }
        require(allocationBytes >= 0 && peakRetainedBytes >= 0 && tailLatencyNanos >= 0) {
            "resource measurements must not be negative"
        }
    }

    public val workloadTotalNanos: Long
        get() = getStackTraceNanos + rawKeyValidationNanos + metadataResolutionNanos
}

public data class TierEPerformanceGateResult(
    public val passed: Boolean,
    public val exceededMetrics: List<String>,
    public val reason: String? = null,
)

/** Compares one mode at a time and refuses to trade exact output for speed. */
public object TierEPerformanceGate {
    public fun evaluate(
        baseline: TierEPerformanceMeasurement,
        candidate: TierEPerformanceMeasurement,
        thresholdPercent: Int = 25,
    ): TierEPerformanceGateResult {
        require(baseline.mode == candidate.mode) { "performance modes must be compared separately" }
        require(thresholdPercent >= 0) { "thresholdPercent must not be negative" }
        if (baseline.logicalStackDigest != candidate.logicalStackDigest) {
            return TierEPerformanceGateResult(
                passed = false,
                exceededMetrics = emptyList(),
                reason = "logical stack output changed",
            )
        }
        val exceeded = buildList {
            compare("workload_total_nanos", baseline.workloadTotalNanos, candidate.workloadTotalNanos, thresholdPercent)?.let(::add)
            compare("get_stack_trace_nanos", baseline.getStackTraceNanos, candidate.getStackTraceNanos, thresholdPercent)?.let(::add)
            compare("raw_key_validation_nanos", baseline.rawKeyValidationNanos, candidate.rawKeyValidationNanos, thresholdPercent)?.let(::add)
            compare("metadata_resolution_nanos", baseline.metadataResolutionNanos, candidate.metadataResolutionNanos, thresholdPercent)?.let(::add)
            compare("allocation_bytes", baseline.allocationBytes, candidate.allocationBytes, thresholdPercent)?.let(::add)
            compare("peak_retained_bytes", baseline.peakRetainedBytes, candidate.peakRetainedBytes, thresholdPercent)?.let(::add)
            compare("tail_latency_nanos", baseline.tailLatencyNanos, candidate.tailLatencyNanos, thresholdPercent)?.let(::add)
        }
        return TierEPerformanceGateResult(passed = exceeded.isEmpty(), exceededMetrics = exceeded)
    }

    private fun compare(
        name: String,
        baseline: Long,
        candidate: Long,
        thresholdPercent: Int,
    ): String? {
        if (baseline == 0L) return null
        return if (candidate.toDouble() > baseline.toDouble() * (100 + thresholdPercent) / 100.0) name else null
    }
}
