package io.mazewall.profiler.tierE.stress

import kotlin.test.Test
import kotlin.test.assertEquals

class TierEOperationOracleTest {
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

        assertEquals(TierEOperationOracle.Report(2, 0, duplicates = 1, wrong = 1, unclassified = 1), report)
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
