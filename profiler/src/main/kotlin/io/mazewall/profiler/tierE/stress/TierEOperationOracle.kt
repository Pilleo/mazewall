package io.mazewall.profiler.tierE.stress

/** Matches benchmark-only write lengths against the resolved Tier E records. */
internal object TierEOperationOracle {
    data class Observation(
        val operationId: Int,
        val resolved: Boolean,
        val hasWorkloadStack: Boolean,
        val hasInvocationIdentity: Boolean,
        val hasExecutionIdentity: Boolean,
    )

    data class Report(
        val expected: Long,
        val matched: Long,
        val candidates: Long = 0,
        val acknowledged: Long = 0,
        val suppressed: Long = 0,
        val unresolved: Long = 0,
        val missing: Long = 0,
        val duplicates: Long = 0,
        val wrong: Long = 0,
        val unclassified: Long = 0,
        /** Bounded diagnostic aid; never substitutes for the aggregate missing count. */
        val missingOperationIdSamples: List<Int> = emptyList(),
    )

    enum class UniqueDecision { CANDIDATE, ACKNOWLEDGED, SUPPRESSED }

    data class UniqueObservation(
        val operationId: Int,
        val decision: UniqueDecision,
        val resolved: Boolean,
        val hasWorkloadStack: Boolean,
        val hasInvocationIdentity: Boolean,
        val hasExecutionIdentity: Boolean,
    )

    fun evaluate(
        expectedOperationIds: Set<Int>,
        observations: List<Observation>,
    ): Report {
        val byId = observations.groupBy { it.operationId }
        var matched = 0L
        var duplicates = 0L
        var wrong = 0L
        var unclassified = 0L
        var missing = 0L
        val missingOperationIdSamples = ArrayList<Int>(MISSING_OPERATION_SAMPLE_LIMIT)
        expectedOperationIds.forEach { operationId ->
            val candidates = byId[operationId].orEmpty()
            if (candidates.size > 1) duplicates += candidates.size - 1L
            val candidate = candidates.singleOrNull()
            when {
                candidate == null && candidates.isNotEmpty() -> Unit
                candidate == null -> {
                    missing++
                    if (missingOperationIdSamples.size < MISSING_OPERATION_SAMPLE_LIMIT) {
                        missingOperationIdSamples += operationId
                    }
                }
                !candidate.resolved ||
                    !candidate.hasWorkloadStack ||
                    !candidate.hasInvocationIdentity ||
                    !candidate.hasExecutionIdentity -> wrong++
                else -> matched++
            }
        }
        observations.filterNot { it.operationId in expectedOperationIds }.forEach { unclassified++ }
        return Report(
            expectedOperationIds.size.toLong(),
            matched,
            missing = missing,
            duplicates = duplicates,
            wrong = wrong,
            unclassified = unclassified,
            missingOperationIdSamples = missingOperationIdSamples.toList(),
        )
    }

    /**
     * Validates unique-edge delivery from collector decisions rather than inferring suppression
     * from absent resolved records. Each declared operation must end in one acknowledgement or
     * one collector-reported suppression; candidates alone are deliberately incomplete.
     */
    fun evaluateUnique(
        expectedOperationIds: Set<Int>,
        observations: List<UniqueObservation>,
        unresolvedOperationIds: Set<Int> = emptySet(),
    ): Report {
        val byId = observations.groupBy { it.operationId }
        var candidates = 0L
        var acknowledged = 0L
        var suppressed = 0L
        var unresolved = 0L
        var missing = 0L
        var duplicates = 0L
        var wrong = 0L
        var unclassified = 0L
        val missingOperationIdSamples = ArrayList<Int>(MISSING_OPERATION_SAMPLE_LIMIT)
        expectedOperationIds.forEach { operationId ->
            val events = byId[operationId].orEmpty()
            val valid = events.filter { it.resolved && it.hasWorkloadStack && it.hasInvocationIdentity && it.hasExecutionIdentity }
            if (events.any { it !in valid }) {
                wrong++
                unresolved++
                return@forEach
            }
            candidates += valid.count { it.decision == UniqueDecision.CANDIDATE }
            val terminals = valid.filter { it.decision != UniqueDecision.CANDIDATE }
            acknowledged += terminals.count { it.decision == UniqueDecision.ACKNOWLEDGED }
            suppressed += terminals.count { it.decision == UniqueDecision.SUPPRESSED }
            when {
                terminals.isEmpty() && operationId in unresolvedOperationIds -> unresolved++
                terminals.isEmpty() -> {
                    missing++
                    if (missingOperationIdSamples.size < MISSING_OPERATION_SAMPLE_LIMIT) {
                        missingOperationIdSamples += operationId
                    }
                }
                terminals.size > 1 -> duplicates += terminals.size - 1L
            }
        }
        observations.filterNot { it.operationId in expectedOperationIds }.forEach { unclassified++ }
        val matched = acknowledged + suppressed
        return Report(
            expected = expectedOperationIds.size.toLong(),
            matched = matched,
            candidates = candidates,
            acknowledged = acknowledged,
            suppressed = suppressed,
            unresolved = unresolved,
            missing = missing,
            duplicates = duplicates,
            wrong = wrong,
            unclassified = unclassified,
            missingOperationIdSamples = missingOperationIdSamples.toList(),
        )
    }

    private const val MISSING_OPERATION_SAMPLE_LIMIT = 16
}
