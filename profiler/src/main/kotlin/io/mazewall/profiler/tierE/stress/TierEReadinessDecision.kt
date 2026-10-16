package io.mazewall.profiler.tierE.stress

/** Auditable acceptance gate for switching the production collector policy. */
internal object TierEReadinessDecision {
    private const val REQUIRED_DECISION_PAIRS = 15
    private const val MINIMUM_CPU_IMPROVEMENT_BASIS_POINTS = 1_500L
    private const val MAXIMUM_WORKLOAD_REGRESSION_BASIS_POINTS = 2_500L
    private const val RETAINED_STATE_BUDGET_BYTES = 64L * 1024 * 1024

    enum class Status(
        val wireName: String,
    ) {
        PROVISIONAL("provisional"),
        ACCEPTED("accepted"),
        REJECTED("rejected"),
    }

    data class Input(
        val runLevel: String,
        val admittedPairs: Int,
        val excludedPairs: Int,
        val cpuImprovementCiLowBasisPoints: Long?,
        val workloadRegressionCiHighBasisPoints: Long?,
        val receiptP99IncreaseCiHighNanos: Long?,
        val receiptP99AllowanceNanos: Long,
        val peakRetainedBytes: Long,
    )

    data class Verdict(
        val status: Status,
        val failures: List<String>,
    )

    fun assess(input: Input): Verdict {
        if (input.runLevel != "decision" || input.admittedPairs < REQUIRED_DECISION_PAIRS) {
            return Verdict(Status.PROVISIONAL, emptyList())
        }
        val failures = buildList {
            if (input.excludedPairs != 0) add("correctness_or_evidence")
            if ((input.cpuImprovementCiLowBasisPoints ?: Long.MIN_VALUE) < MINIMUM_CPU_IMPROVEMENT_BASIS_POINTS) {
                add("collector_cpu_improvement")
            }
            if ((input.workloadRegressionCiHighBasisPoints ?: Long.MAX_VALUE) > MAXIMUM_WORKLOAD_REGRESSION_BASIS_POINTS) {
                add("workload_latency")
            }
            if ((input.receiptP99IncreaseCiHighNanos ?: Long.MAX_VALUE) > input.receiptP99AllowanceNanos) {
                add("receipt_p99")
            }
            if (input.peakRetainedBytes > RETAINED_STATE_BUDGET_BYTES) add("retained_state")
        }
        return Verdict(if (failures.isEmpty()) Status.ACCEPTED else Status.REJECTED, failures)
    }
}
