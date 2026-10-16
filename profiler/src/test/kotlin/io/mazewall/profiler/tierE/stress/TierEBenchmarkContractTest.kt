package io.mazewall.profiler.tierE.stress

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TierEBenchmarkContractTest {
    @Test
    fun `verified baseline is valid workload evidence without profiler session evidence`() {
        val verdict = TierEBenchmarkContract.evaluate(
            tierE = false,
            userNotif = false,
            requiresWorkloadFrames = false,
            evidence = TierEBenchmarkContract.Evidence(controlledWorkloadVerified = true),
        )

        assertEquals(TierEBenchmarkContract.CorrectnessState.PASS, verdict.workloadCorrectness)
        assertEquals(TierEBenchmarkContract.EvidenceState.NOT_APPLICABLE, verdict.wholeSessionEvidence)
    }

    @Test
    fun `USER_NOTIF diagnostics accept an explicit bounded worker deadline`() {
        val limits = UserNotifOracleSmoke.diagnosticLimits(480)

        assertEquals(480_000L, limits.workerTimeoutMillis)
        assertEquals(5_000L, limits.workerGraceMillis)
        assertFailsWith<IllegalArgumentException> { UserNotifOracleSmoke.diagnosticLimits(0) }
    }

    @Test
    fun `expected no-active-invocation background does not invalidate controlled workload`() {
        val verdict = TierEBenchmarkContract.evaluate(
            tierE = true,
            userNotif = false,
            requiresWorkloadFrames = false,
            evidence = TierEBenchmarkContract.Evidence(
                observations = 30,
                workloadResolved = 2,
                workloadUnlinksResolved = 1,
                evidenceComplete = false,
                operationOracle = TierEBenchmarkContract.OperationOracle(available = true, expected = 1, matched = 1),
            ),
        )

        assertTrue(verdict.workloadCorrect)
        assertEquals(TierEBenchmarkContract.EvidenceState.INCOMPLETE, verdict.wholeSessionEvidence)
    }

    @Test
    fun `USER NOTIF drops make evidence incomplete without failing an independently matched workload`() {
        val verdict = TierEBenchmarkContract.evaluate(
            tierE = false,
            userNotif = true,
            requiresWorkloadFrames = false,
            evidence = TierEBenchmarkContract.Evidence(
                dropped = 7,
                evidenceComplete = false,
                operationOracle = TierEBenchmarkContract.OperationOracle(available = true, expected = 2, matched = 2),
            ),
        )

        assertTrue(verdict.workloadCorrect)
        assertEquals(TierEBenchmarkContract.EvidenceState.INCOMPLETE, verdict.wholeSessionEvidence)
        assertTrue(verdict.failures.isEmpty())
    }

    @Test
    fun `missing controlled unlink and unexplained unknown fail workload verdict`() {
        val verdict = TierEBenchmarkContract.evaluate(
            tierE = true,
            userNotif = false,
            requiresWorkloadFrames = false,
            evidence = TierEBenchmarkContract.Evidence(
                observations = 30,
                workloadResolved = 2,
                unknownOther = 1,
                evidenceComplete = true,
            ),
        )

        assertFalse(verdict.workloadCorrect)
        assertEquals(listOf("missing_workload_unlink", "unexplained_unknown"), verdict.failures)
    }

    @Test
    fun `observer-free unique edge cost lane requires an independently verified workload`() {
        val verdict = TierEBenchmarkContract.evaluate(
            tierE = true,
            userNotif = false,
            requiresWorkloadFrames = false,
            evidence = TierEBenchmarkContract.Evidence(
                observations = 1,
                workloadResolved = 1,
                evidenceComplete = true,
                controlledWorkloadVerified = true,
                observerFreeUniqueEdgeMeasurement = true,
            ),
        )

        assertEquals(TierEBenchmarkContract.CorrectnessState.PASS, verdict.workloadCorrectness)
        assertEquals(TierEBenchmarkContract.EvidenceState.COMPLETE, verdict.wholeSessionEvidence)
    }

    @Test
    fun `user notif stack and no-stack claims are verified separately`() {
        val stackVerdict = TierEBenchmarkContract.evaluate(
            tierE = false,
            userNotif = true,
            requiresWorkloadFrames = true,
            evidence = TierEBenchmarkContract.Evidence(
                dropped = 0,
                workloadFrames = true,
                operationOracle = TierEBenchmarkContract.OperationOracle(available = true, expected = 1, matched = 1),
            ),
        )
        val noStackVerdict = TierEBenchmarkContract.evaluate(
            tierE = false,
            userNotif = true,
            requiresWorkloadFrames = false,
            evidence = TierEBenchmarkContract.Evidence(
                dropped = 0,
                workloadFrames = true,
                operationOracle = TierEBenchmarkContract.OperationOracle(available = true, expected = 1, matched = 1),
            ),
        )

        assertTrue(stackVerdict.workloadCorrect)
        assertFalse(noStackVerdict.workloadCorrect)
    }

    @Test
    fun `user notif requires a nonempty operation oracle`() {
        val verdict = TierEBenchmarkContract.evaluate(
            tierE = false,
            userNotif = true,
            requiresWorkloadFrames = false,
            evidence = TierEBenchmarkContract.Evidence(dropped = 0),
        )

        assertEquals(TierEBenchmarkContract.CorrectnessState.FAILED, verdict.workloadCorrectness)
        assertEquals(listOf("operation_oracle_unavailable"), verdict.failures)
    }

    @Test
    fun `user notif rejects an empty operation manifest`() {
        val verdict = TierEBenchmarkContract.evaluate(
            tierE = false,
            userNotif = true,
            requiresWorkloadFrames = false,
            evidence = TierEBenchmarkContract.Evidence(
                dropped = 0,
                operationOracle = TierEBenchmarkContract.OperationOracle(available = true),
            ),
        )

        assertEquals(TierEBenchmarkContract.CorrectnessState.FAILED, verdict.workloadCorrectness)
        assertEquals(listOf("no_expected_operations"), verdict.failures)
    }

    @Test
    fun `application user notif measurement remains explicitly unverified without an operation oracle`() {
        val verdict = TierEBenchmarkContract.evaluate(
            tierE = false,
            userNotif = true,
            requiresWorkloadFrames = false,
            evidence = TierEBenchmarkContract.Evidence(dropped = 0),
            requiresOperationOracle = false,
        )

        assertEquals(TierEBenchmarkContract.CorrectnessState.UNVERIFIED, verdict.workloadCorrectness)
        assertTrue(verdict.failures.isEmpty())
    }

    @Test
    fun `aggregate stack counts are explicitly unverified without operation identity mapping`() {
        val verdict = TierEBenchmarkContract.evaluate(
            tierE = true,
            userNotif = false,
            requiresWorkloadFrames = false,
            evidence = TierEBenchmarkContract.Evidence(
                observations = 5,
                workloadResolved = 5,
                workloadUnlinksResolved = 1,
            ),
        )

        assertEquals(TierEBenchmarkContract.CorrectnessState.UNVERIFIED, verdict.workloadCorrectness)
        assertFalse(verdict.workloadCorrect)
    }

    @Test
    fun `identity oracle rejects missing duplicate wrong and unclassified operations`() {
        val verdict = TierEBenchmarkContract.evaluate(
            tierE = true,
            userNotif = false,
            requiresWorkloadFrames = false,
            evidence = TierEBenchmarkContract.Evidence(
                observations = 4,
                workloadResolved = 4,
                workloadUnlinksResolved = 1,
                operationOracle = TierEBenchmarkContract.OperationOracle(
                    available = true,
                    expected = 2,
                    matched = 1,
                    duplicates = 1,
                    wrong = 1,
                    unclassified = 1,
                ),
            ),
        )

        assertEquals(TierEBenchmarkContract.CorrectnessState.FAILED, verdict.workloadCorrectness)
        assertEquals(
            listOf("missing_or_extra_operations", "duplicate_operations", "wrong_operation_identity", "unclassified_operation"),
            verdict.failures,
        )
    }

    @Test
    fun `strict operation oracle does not require legacy unlink evidence`() {
        val verdict = TierEBenchmarkContract.evaluate(
            tierE = true,
            userNotif = false,
            requiresWorkloadFrames = false,
            evidence = TierEBenchmarkContract.Evidence(
                observations = 1,
                workloadResolved = 1,
                operationOracle = TierEBenchmarkContract.OperationOracle(available = true, expected = 1, matched = 1),
            ),
        )

        assertTrue(verdict.workloadCorrect)
    }

    @Test
    fun `callback suppression requires nested proof and rejects restored outer operations`() {
        val accepted = TierEBenchmarkContract.evaluate(
            tierE = true,
            userNotif = false,
            requiresWorkloadFrames = false,
            evidence = TierEBenchmarkContract.Evidence(
                observations = 1,
                workloadResolved = 1,
                ambientInactive = 1,
                callbackSuppressionScenario = true,
                callbackProbeResolved = 1,
                operationOracle = TierEBenchmarkContract.OperationOracle(available = true, expected = 4),
            ),
        )
        val resurrected = TierEBenchmarkContract.evaluate(
            tierE = true,
            userNotif = false,
            requiresWorkloadFrames = false,
            evidence = TierEBenchmarkContract.Evidence(
                observations = 1,
                workloadResolved = 1,
                ambientInactive = 1,
                callbackSuppressionScenario = true,
                callbackProbeResolved = 1,
                operationOracle = TierEBenchmarkContract.OperationOracle(available = true, expected = 4, matched = 1),
            ),
        )
        val reportedOmission = TierEBenchmarkContract.evaluate(
            tierE = true,
            userNotif = false,
            requiresWorkloadFrames = false,
            evidence = TierEBenchmarkContract.Evidence(
                observations = 1,
                workloadResolved = 1,
                ambientInactive = 1,
                callbackSuppressionScenario = true,
                callbackProbeResolved = 1,
                operationOracle = TierEBenchmarkContract.OperationOracle(available = true, expected = 4, missing = 4),
            ),
        )

        assertTrue(accepted.workloadCorrect)
        assertEquals(TierEBenchmarkContract.EvidenceState.INCOMPLETE, accepted.wholeSessionEvidence)
        assertTrue(reportedOmission.workloadCorrect)
        assertEquals(
            listOf("callback_suppression_resurrected_outer_operation"),
            resurrected.failures,
        )
    }

    @Test
    fun `forced capture failure remains a passing workload with incomplete evidence`() {
        val verdict = TierEBenchmarkContract.evaluate(
            tierE = true,
            userNotif = false,
            requiresWorkloadFrames = false,
            evidence = TierEBenchmarkContract.Evidence(
                observations = 1,
                workloadResolved = 1,
                evidenceComplete = true,
                forcedCaptureFailureScenario = true,
                operationOracle = TierEBenchmarkContract.OperationOracle(available = true, expected = 2, matched = 1),
            ),
        )

        assertTrue(verdict.workloadCorrect)
        assertEquals(TierEBenchmarkContract.EvidenceState.INCOMPLETE, verdict.wholeSessionEvidence)
    }

    @Test
    fun `unique edge oracle accepts all accounted representatives for repeated declared operations`() {
        val verdict = TierEBenchmarkContract.evaluate(
            tierE = true,
            userNotif = false,
            requiresWorkloadFrames = false,
            evidence = TierEBenchmarkContract.Evidence(
                observations = 1,
                workloadResolved = 1,
                operationOracle = TierEBenchmarkContract.OperationOracle(
                    available = true,
                    expected = 3,
                    matched = 3,
                    representativeOnly = true,
                    candidates = 1,
                    acknowledged = 1,
                    suppressed = 2,
                ),
            ),
        )

        assertTrue(verdict.workloadCorrect)
    }

    @Test
    fun `unique edge oracle rejects missing suppression accounting`() {
        val verdict = TierEBenchmarkContract.evaluate(
            tierE = true,
            userNotif = false,
            requiresWorkloadFrames = false,
            evidence = TierEBenchmarkContract.Evidence(
                observations = 1,
                workloadResolved = 1,
                operationOracle = TierEBenchmarkContract.OperationOracle(
                    available = true,
                    expected = 3,
                    matched = 2,
                    representativeOnly = true,
                ),
            ),
        )

        assertFalse(verdict.workloadCorrect)
        assertEquals(listOf("missing_or_extra_operations"), verdict.failures)
    }
}
