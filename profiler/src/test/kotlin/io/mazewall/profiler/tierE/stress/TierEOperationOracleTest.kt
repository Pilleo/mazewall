package io.mazewall.profiler.tierE.stress

import kotlin.test.Test
import kotlin.test.assertEquals

class TierEOperationOracleTest {
    @Test
    fun `raw diagnostic saturation makes USER NOTIF operation identity unverified`() {
        assertEquals(true, UserNotifOracleSmoke.operationOracleAvailable(sharedOpenatOracle = true, diagnosticDrops = 0))
        assertEquals(false, UserNotifOracleSmoke.operationOracleAvailable(sharedOpenatOracle = true, diagnosticDrops = 1))
        assertEquals(false, UserNotifOracleSmoke.operationOracleAvailable(sharedOpenatOracle = false, diagnosticDrops = 0))
    }

    @Test
    fun `shared openat oracle uses directory fd from syscall argument zero`() {
        assertEquals(73, UserNotifOracleSmoke.sharedOpenatOperationId(listOf(73L, 1L, 2L, 999L)))
    }

    @Test
    fun `matches complete uniquely identified resolved operations`() {
        val report = TierEOperationOracle.evaluate(
            setOf(4096, 4097),
            listOf(
                TierEOperationOracle.Observation(4096, true, true, true, true),
                TierEOperationOracle.Observation(4097, true, true, true, true),
            ),
        )

        assertEquals(TierEOperationOracle.Report(2, 2, 0, 0, 0), report)
    }

    @Test
    fun `reports duplicate wrong and unclassified records`() {
        val report = TierEOperationOracle.evaluate(
            setOf(4096, 4097),
            listOf(
                TierEOperationOracle.Observation(4096, true, true, true, true),
                TierEOperationOracle.Observation(4096, true, true, true, true),
                TierEOperationOracle.Observation(4097, true, false, true, true),
                TierEOperationOracle.Observation(5000, true, true, true, true),
            ),
        )

        assertEquals(
            TierEOperationOracle.Report(
                2,
                0,
                duplicates = 1,
                wrong = 1,
                unclassified = 1,
                wrongOperationIdSamples = listOf(4097),
            ),
            report,
        )
    }

    @Test
    fun `reports the causal marker-loss reason for an observed but unresolved operation`() {
        val report = TierEOperationOracle.evaluate(
            setOf(4096),
            listOf(
                TierEOperationOracle.Observation(
                    operationId = 4096,
                    resolved = false,
                    hasWorkloadStack = false,
                    hasInvocationIdentity = false,
                    hasExecutionIdentity = false,
                    markerLossReason = TierEOperationOracle.MarkerLossReason.DETACHED,
                ),
            ),
        )

        assertEquals(1, report.wrong)
        assertEquals(listOf(4096), report.wrongOperationIdSamples)
        assertEquals(mapOf(TierEOperationOracle.MarkerLossReason.DETACHED to 1L), report.markerLossReasons)
    }

    @Test
    fun `reports inactive invocation records independently of detached marker records`() {
        val report = TierEOperationOracle.evaluate(
            setOf(4096),
            listOf(
                TierEOperationOracle.Observation(
                    operationId = 4096,
                    resolved = false,
                    hasWorkloadStack = false,
                    hasInvocationIdentity = false,
                    hasExecutionIdentity = false,
                    markerLossReason = TierEOperationOracle.MarkerLossReason.INACTIVE_INVOCATION,
                ),
            ),
        )

        assertEquals(mapOf(TierEOperationOracle.MarkerLossReason.INACTIVE_INVOCATION to 1L), report.markerLossReasons)
    }

    @Test
    fun `reports unavailable marker state independently of task-storage absence`() {
        val report = TierEOperationOracle.evaluate(
            setOf(4096),
            listOf(
                TierEOperationOracle.Observation(
                    operationId = 4096,
                    resolved = false,
                    hasWorkloadStack = false,
                    hasInvocationIdentity = false,
                    hasExecutionIdentity = false,
                    markerLossReason = TierEOperationOracle.MarkerLossReason.MARKER_STATE_UNAVAILABLE,
                ),
            ),
        )

        assertEquals(mapOf(TierEOperationOracle.MarkerLossReason.MARKER_STATE_UNAVAILABLE to 1L), report.markerLossReasons)
    }

    @Test
    fun `unique accounting requires explicit collector suppression rather than inferred absence`() {
        val expected = setOf(10, 11, 12)
        val report = TierEOperationOracle.evaluateUnique(
            expected,
            listOf(
                unique(10, TierEOperationOracle.UniqueDecision.CANDIDATE),
                unique(10, TierEOperationOracle.UniqueDecision.ACKNOWLEDGED),
                unique(11, TierEOperationOracle.UniqueDecision.SUPPRESSED),
                unique(12, TierEOperationOracle.UniqueDecision.SUPPRESSED),
            ),
        )

        assertEquals(3, report.matched)
        assertEquals(1, report.candidates)
        assertEquals(1, report.acknowledged)
        assertEquals(2, report.suppressed)
        assertEquals(0, report.missing)
    }

    @Test
    fun `unique accounting does not call a missing operation suppressed`() {
        val report = TierEOperationOracle.evaluateUnique(
            setOf(10, 11),
            listOf(unique(10, TierEOperationOracle.UniqueDecision.ACKNOWLEDGED)),
        )

        assertEquals(1, report.matched)
        assertEquals(1, report.missing)
        assertEquals(0, report.suppressed)
        assertEquals(listOf(11), report.missingOperationIdSamples)
    }

    @Test
    fun `unique accounting reports observed resolution failure separately from missing`() {
        val report = TierEOperationOracle.evaluateUnique(
            setOf(10, 11),
            listOf(unique(10, TierEOperationOracle.UniqueDecision.ACKNOWLEDGED)),
            unresolvedOperationIds = setOf(11),
        )

        assertEquals(0, report.missing)
        assertEquals(1, report.unresolved)
    }

    private fun unique(
        operationId: Int,
        decision: TierEOperationOracle.UniqueDecision,
    ) = TierEOperationOracle.UniqueObservation(operationId, decision, true, true, true, true)
}
