package io.mazewall.profiler.tierE.stress

/**
 * Fail-closed admission contract for sustained collector measurements.
 * It deliberately separates an overload experiment from a valid performance sample.
 */
internal object TierESustainedCollectorDecision {
    internal enum class Load {
        BELOW_CAPACITY,
        NEAR_CAPACITY,
        INTENTIONAL_OVERLOAD,
        ;

        internal val wireName: String
            get() = name.lowercase()

        companion object {
            internal fun fromProperty(value: String): Load =
                entries.singleOrNull { it.wireName == value }
                    ?: error("unknown Tier E sustained collector load: $value")
        }
    }

    internal enum class EvidenceIntegrity {
        COMPLETE,
        INCOMPLETE,
    }

    internal data class Sample(
        val load: Load,
        val requestedOperations: Long,
        val achievedOperations: Long,
        val resolvedOperations: Long,
        val observedLosses: Long,
        val retainedResultDrops: Long = 0,
        val pendingObservationBudgetDrops: Long = 0,
        val pendingObservationQueueDrops: Long = 0,
        val producerClosed: Boolean,
        val evidenceComplete: Boolean,
    ) {
        init {
            require(requestedOperations >= 0) { "requested operations must be non-negative" }
            require(achievedOperations in 0..requestedOperations) { "achieved operations must be within the request" }
            require(resolvedOperations in 0..achievedOperations) { "resolved operations must be within achieved operations" }
            require(observedLosses >= 0) { "observed losses must be non-negative" }
            require(retainedResultDrops >= 0) { "retained result drops must be non-negative" }
            require(pendingObservationBudgetDrops >= 0) { "pending observation budget drops must be non-negative" }
            require(pendingObservationQueueDrops >= 0) { "pending observation queue drops must be non-negative" }
        }
    }

    internal data class Verdict(
        val evidenceIntegrity: EvidenceIntegrity,
        val performanceEligible: Boolean,
        val exclusionReason: String?,
    )

    internal fun evaluate(sample: Sample): Verdict {
        if (sample.load == Load.INTENTIONAL_OVERLOAD) {
            return Verdict(EvidenceIntegrity.INCOMPLETE, performanceEligible = false, exclusionReason = "intentional_overload")
        }
        if (!sample.producerClosed) {
            return Verdict(EvidenceIntegrity.INCOMPLETE, performanceEligible = false, exclusionReason = "producer_not_closed")
        }
        if (!sample.evidenceComplete) {
            return Verdict(EvidenceIntegrity.INCOMPLETE, performanceEligible = false, exclusionReason = "terminal_evidence_incomplete")
        }
        if (sample.retainedResultDrops != 0L) {
            return Verdict(EvidenceIntegrity.INCOMPLETE, performanceEligible = false, exclusionReason = "retained_result_drop")
        }
        if (sample.pendingObservationBudgetDrops != 0L) {
            return Verdict(EvidenceIntegrity.INCOMPLETE, performanceEligible = false, exclusionReason = "pending_observation_budget_drop")
        }
        if (sample.pendingObservationQueueDrops != 0L) {
            return Verdict(EvidenceIntegrity.INCOMPLETE, performanceEligible = false, exclusionReason = "pending_observation_queue_drop")
        }
        if (sample.observedLosses != 0L) {
            return Verdict(EvidenceIntegrity.INCOMPLETE, performanceEligible = false, exclusionReason = "observed_ring_loss")
        }
        if (sample.resolvedOperations != sample.achievedOperations) {
            return Verdict(EvidenceIntegrity.INCOMPLETE, performanceEligible = false, exclusionReason = "operation_reconciliation_failed")
        }
        return Verdict(EvidenceIntegrity.COMPLETE, performanceEligible = true, exclusionReason = null)
    }
}
