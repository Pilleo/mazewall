package io.mazewall.profiler.tierE.stress

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TierESustainedCollectorDecisionTest {
    @Test
    fun `load values have stable property names`() {
        assertEquals(
            TierESustainedCollectorDecision.Load.NEAR_CAPACITY,
            TierESustainedCollectorDecision.Load.fromProperty("near_capacity"),
        )
    }

    @Test
    fun `below capacity complete sample is eligible for performance analysis`() {
        val verdict = TierESustainedCollectorDecision.evaluate(
            TierESustainedCollectorDecision.Sample(
                load = TierESustainedCollectorDecision.Load.BELOW_CAPACITY,
                requestedOperations = 1_000,
                achievedOperations = 1_000,
                resolvedOperations = 1_000,
                observedLosses = 0,
                producerClosed = true,
                evidenceComplete = true,
            ),
        )

        assertTrue(verdict.performanceEligible)
        assertEquals(TierESustainedCollectorDecision.EvidenceIntegrity.COMPLETE, verdict.evidenceIntegrity)
    }

    @Test
    fun `overload is explicitly incomplete and excluded even when counts reconcile`() {
        val verdict = TierESustainedCollectorDecision.evaluate(
            TierESustainedCollectorDecision.Sample(
                load = TierESustainedCollectorDecision.Load.INTENTIONAL_OVERLOAD,
                requestedOperations = 1_000,
                achievedOperations = 1_000,
                resolvedOperations = 900,
                observedLosses = 100,
                producerClosed = true,
                evidenceComplete = false,
            ),
        )

        assertFalse(verdict.performanceEligible)
        assertEquals(TierESustainedCollectorDecision.EvidenceIntegrity.INCOMPLETE, verdict.evidenceIntegrity)
        assertEquals("intentional_overload", verdict.exclusionReason)
    }

    @Test
    fun `missing producer closure is incomplete regardless of observed events`() {
        val verdict = TierESustainedCollectorDecision.evaluate(
            TierESustainedCollectorDecision.Sample(
                load = TierESustainedCollectorDecision.Load.NEAR_CAPACITY,
                requestedOperations = 100,
                achievedOperations = 100,
                resolvedOperations = 100,
                observedLosses = 0,
                producerClosed = false,
                evidenceComplete = true,
            ),
        )

        assertFalse(verdict.performanceEligible)
        assertEquals("producer_not_closed", verdict.exclusionReason)
    }
}
