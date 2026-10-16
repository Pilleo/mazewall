package io.mazewall.profiler.tierE.stress

import kotlin.test.Test
import kotlin.test.assertEquals

class TierEReadinessDecisionTest {
    @Test
    fun `accepts a complete decision run only when every performance gate passes`() {
        val verdict = TierEReadinessDecision.assess(
            TierEReadinessDecision.Input(
                runLevel = "decision",
                admittedPairs = 15,
                excludedPairs = 0,
                cpuImprovementCiLowBasisPoints = 1_500,
                workloadRegressionCiHighBasisPoints = 2_500,
                receiptP99IncreaseCiHighNanos = 100_000,
                receiptP99AllowanceNanos = 100_000,
                peakRetainedBytes = 64L * 1024 * 1024,
            ),
        )

        assertEquals(TierEReadinessDecision.Status.ACCEPTED, verdict.status)
        assertEquals(emptyList(), verdict.failures)
    }

    @Test
    fun `keeps smoke and development evidence provisional`() {
        val verdict = TierEReadinessDecision.assess(passingInput(runLevel = "development", pairs = 5))

        assertEquals(TierEReadinessDecision.Status.PROVISIONAL, verdict.status)
    }

    @Test
    fun `rejects a decision when cpu confidence misses the fifteen percent gate`() {
        val verdict = TierEReadinessDecision.assess(
            passingInput().copy(cpuImprovementCiLowBasisPoints = 1_499),
        )

        assertEquals(TierEReadinessDecision.Status.REJECTED, verdict.status)
        assertEquals(listOf("collector_cpu_improvement"), verdict.failures)
    }

    private fun passingInput(
        runLevel: String = "decision",
        pairs: Int = 15,
    ) = TierEReadinessDecision.Input(
        runLevel = runLevel,
        admittedPairs = pairs,
        excludedPairs = 0,
        cpuImprovementCiLowBasisPoints = 2_000,
        workloadRegressionCiHighBasisPoints = 1_000,
        receiptP99IncreaseCiHighNanos = 50_000,
        receiptP99AllowanceNanos = 100_000,
        peakRetainedBytes = 32L * 1024 * 1024,
    )
}
