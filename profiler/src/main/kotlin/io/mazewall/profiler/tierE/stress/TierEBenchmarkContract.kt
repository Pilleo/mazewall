package io.mazewall.profiler.tierE.stress

/** Separates the controlled-workload claim from complete process-wide evidence. */
internal object TierEBenchmarkContract {
    fun evaluate(
        tierE: Boolean,
        userNotif: Boolean,
        requiresWorkloadFrames: Boolean,
        evidence: Evidence,
        requiresOperationOracle: Boolean = userNotif,
    ): Verdict {
        val workloadFailures = buildList {
            if (tierE && evidence.observations == 0L) add("no_observations")
            if (tierE && evidence.workloadResolved == 0L) add("missing_workload_stack")
            if (tierE && !evidence.operationOracle.available && evidence.workloadUnlinksResolved == 0L) add("missing_workload_unlink")
            if (tierE && evidence.wrong != 0L) add("wrong_workload_stack")
            if (tierE && evidence.lost != 0L) add("ring_loss")
            if (tierE && evidence.unknownOther != 0L) add("unexplained_unknown")
            if (requiresWorkloadFrames && !evidence.workloadFrames) add("missing_workload_frames")
            if (userNotif && !requiresWorkloadFrames && evidence.workloadFrames) add("unexpected_workload_frames")
        }
        val oracleFailures = buildList {
            if (requiresOperationOracle && !evidence.operationOracle.available) add("operation_oracle_unavailable")
            if (evidence.operationOracle.available) {
                if (evidence.operationOracle.expected == 0L) add("no_expected_operations")
                val expectedMatches = if (evidence.operationOracle.representativeOnly) {
                    evidence.operationOracle.expected > 0L &&
                        evidence.operationOracle.matched == evidence.operationOracle.expected &&
                        evidence.operationOracle.acknowledged > 0L &&
                        evidence.operationOracle.missing == 0L &&
                        evidence.operationOracle.unresolved == 0L
                } else {
                    evidence.operationOracle.expected == evidence.operationOracle.matched
                }
                if (!expectedMatches) add("missing_or_extra_operations")
                if (evidence.operationOracle.duplicates != 0L) add("duplicate_operations")
                if (evidence.operationOracle.wrong != 0L) add("wrong_operation_identity")
                if (evidence.operationOracle.unclassified != 0L) add("unclassified_operation")
            }
        }
        val failures = workloadFailures + oracleFailures
        return Verdict(
            workloadCorrectness = when {
                failures.isNotEmpty() -> CorrectnessState.FAILED
                !evidence.operationOracle.available && !evidence.controlledWorkloadVerified -> CorrectnessState.UNVERIFIED
                else -> CorrectnessState.PASS
            },
            wholeSessionEvidence = if (tierE || userNotif) {
                if (evidence.evidenceComplete) EvidenceState.COMPLETE else EvidenceState.INCOMPLETE
            } else {
                EvidenceState.NOT_APPLICABLE
            },
            failures = failures,
        )
    }

    internal data class Evidence(
        val observations: Long = 0,
        val workloadResolved: Long = 0,
        val workloadUnlinksResolved: Long = 0,
        val wrong: Long = 0,
        val lost: Long = 0,
        val unknownOther: Long = 0,
        val dropped: Long = 0,
        val workloadFrames: Boolean = false,
        val evidenceComplete: Boolean = false,
        /** Baseline child completed its independently checked, declared workload. */
        val controlledWorkloadVerified: Boolean = false,
        val operationOracle: OperationOracle = OperationOracle(),
    )

    /** Independent expected-to-observed operation accounting for benchmark-only fixtures. */
    internal data class OperationOracle(
        val available: Boolean = false,
        val expected: Long = 0,
        val matched: Long = 0,
        /** Unique-edge mode retains one acknowledged representative for repeated logical operations. */
        val representativeOnly: Boolean = false,
        /** Candidate delivery attempts returned by the collector before acknowledgement. */
        val candidates: Long = 0,
        /** Unique representatives acknowledged by the session's retained sink. */
        val acknowledged: Long = 0,
        /** Explicit collector decisions after an acknowledged edge, never inferred from absence. */
        val suppressed: Long = 0,
        /** Controlled operations whose attribution was invalid or unresolved. */
        val unresolved: Long = 0,
        /** Controlled operations with no collector terminal decision. */
        val missing: Long = 0,
        val duplicates: Long = 0,
        val wrong: Long = 0,
        val unclassified: Long = 0,
    )

    internal data class Verdict(
        val workloadCorrectness: CorrectnessState,
        val wholeSessionEvidence: EvidenceState,
        val failures: List<String>,
    ) {
        /** Compatibility view for callers that only need a strict passing claim. */
        val workloadCorrect: Boolean get() = workloadCorrectness == CorrectnessState.PASS
    }

    internal enum class CorrectnessState { PASS, FAILED, UNVERIFIED }

    internal enum class EvidenceState { COMPLETE, INCOMPLETE, NOT_APPLICABLE }
}
