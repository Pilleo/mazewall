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
            if (tierE && !evidence.expectedInactiveLifecycleScenario && evidence.observations == 0L) add("no_observations")
            if (tierE && !evidence.expectedInactiveLifecycleScenario && evidence.workloadResolved == 0L) add("missing_workload_stack")
            if (
                tierE &&
                !evidence.expectedInactiveLifecycleScenario &&
                evidence.requiresUnlinkObservation &&
                !evidence.observerFreeUniqueEdgeMeasurement &&
                !evidence.operationOracle.available &&
                evidence.workloadUnlinksResolved == 0L
            ) {
                add("missing_workload_unlink")
            }
            if (tierE && evidence.wrong != 0L) add("wrong_workload_stack")
            if (tierE && !evidence.expectedIncompleteLifecycleScenario && evidence.lost != 0L) add("ring_loss")
            if (tierE && !evidence.expectedIncompleteLifecycleScenario && evidence.unknownOther != 0L) add("unexplained_unknown")
            if (tierE && evidence.callbackSuppressionScenario && evidence.ambientInactive == 0L) add("missing_callback_inactive_diagnostic")
            if (tierE && evidence.callbackSuppressionScenario && evidence.callbackProbeResolved == 0L) add("missing_callback_suppression_probe")
            if (requiresWorkloadFrames && !evidence.workloadFrames) add("missing_workload_frames")
            if (userNotif && !requiresWorkloadFrames && evidence.workloadFrames) add("unexpected_workload_frames")
        }
        val oracleFailures = buildList {
            if (requiresOperationOracle && !evidence.operationOracle.available) add("operation_oracle_unavailable")
            if (evidence.operationOracle.available && evidence.callbackSuppressionScenario) {
                if (evidence.operationOracle.expected == 0L) add("no_expected_operations")
                if (
                    evidence.operationOracle.matched != 0L ||
                    evidence.operationOracle.candidates != 0L ||
                    evidence.operationOracle.acknowledged != 0L ||
                    evidence.operationOracle.suppressed != 0L ||
                    evidence.operationOracle.unresolved != 0L ||
                    (
                        evidence.operationOracle.missing != 0L &&
                        evidence.operationOracle.missing != evidence.operationOracle.expected
                    )
                ) {
                    add("callback_suppression_resurrected_outer_operation")
                }
                if (evidence.operationOracle.duplicates != 0L) add("duplicate_operations")
                if (evidence.operationOracle.wrong != 0L) add("wrong_operation_identity")
                if (evidence.operationOracle.unclassified != 0L) add("unclassified_operation")
            } else if (evidence.operationOracle.available && !evidence.expectedIncompleteLifecycleScenario) {
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
                if (evidence.evidenceComplete && !evidence.callbackSuppressionScenario && !evidence.expectedIncompleteLifecycleScenario) {
                    EvidenceState.COMPLETE
                } else {
                    EvidenceState.INCOMPLETE
                }
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
        val requiresUnlinkObservation: Boolean = true,
        val wrong: Long = 0,
        val lost: Long = 0,
        val unknownOther: Long = 0,
        /** Aggregate inactive task-storage observations; no per-syscall record is retained. */
        val ambientInactive: Long = 0,
        val dropped: Long = 0,
        val workloadFrames: Boolean = false,
        val evidenceComplete: Boolean = false,
        /** Baseline child completed its independently checked, declared workload. */
        val controlledWorkloadVerified: Boolean = false,
        /** A deterministic fixture detached attribution before releasing its controlled syscall. */
        val detachedInactiveScenario: Boolean = false,
        /** A deterministic fixture terminally closes the source before releasing its controlled syscall. */
        val terminalWhileActiveScenario: Boolean = false,
        /** A test-only native fault rejected exact-stack admission after the workload gate opened. */
        val forcedCaptureFailureScenario: Boolean = false,
        /** A test-only native fault replaced the JVM after an exact marker was admitted. */
        val execTransitionScenario: Boolean = false,
        /** A Java callback invalidated the outer native scope before the controlled operation resumed. */
        val callbackSuppressionScenario: Boolean = false,
        /** Exact nested callback evidence; inactive observations remain aggregate diagnostics. */
        val callbackProbeResolved: Long = 0,
        /** Paired production-cost lane without the benchmark-only unique-edge decision observer. */
        val observerFreeUniqueEdgeMeasurement: Boolean = false,
        val operationOracle: OperationOracle = OperationOracle(),
    ) {
        val expectedIncompleteLifecycleScenario: Boolean
            get() = detachedInactiveScenario || terminalWhileActiveScenario || forcedCaptureFailureScenario || execTransitionScenario

        val expectedInactiveLifecycleScenario: Boolean
            get() = expectedIncompleteLifecycleScenario || callbackSuppressionScenario
    }

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
