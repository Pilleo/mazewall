package io.mazewall.profiler.tierE.stress

import io.mazewall.profiler.tierE.engine.TierEBenchmarkAuditMapReader.InvocationWitnessCount
import io.mazewall.profiler.tierE.engine.TierEPreGateAuditCount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TierEInvocationWitnessComparisonTest {
    private fun stages(
        raw: Long,
        main: Long,
    ) = TierEPreGateAuditCount(1, raw, main, raw, main)

    @Test
    fun `earliest ID-specific token separates program entry from FD gate`() {
        val result = TierEInvocationWitnessComparison.classify(
            mapOf(13 to 42L, 14 to 43L, 15 to 44L, 16 to 45L),
            mapOf(
                42L to InvocationWitnessCount(1, 0),
                43L to InvocationWitnessCount(1, 1),
                44L to InvocationWitnessCount(0, 1),
            ),
            mapOf(13 to stages(1, 0), 14 to stages(0, 1), 15 to stages(0, 1), 16 to stages(0, 0)),
        )
        assertEquals("MAIN_BEFORE_TOKEN", result.getValue(13))
        assertEquals("RAW_AFTER_TOKEN", result.getValue(14))
        assertEquals("RAW_BEFORE_TOKEN", result.getValue(15))
        assertEquals("BOTH_BEFORE_TOKEN", result.getValue(16))
    }

    @Test
    fun `missing or duplicate token cannot be counted as complete FD evidence`() {
        assertThrows(IllegalStateException::class.java) {
            TierEInvocationWitnessComparison.classify(mapOf(13 to 42L), emptyMap(), mapOf(13 to stages(1, 0)))
        }
        assertThrows(IllegalStateException::class.java) {
            TierEInvocationWitnessComparison.classify(
                mapOf(13 to 42L),
                mapOf(42L to InvocationWitnessCount(2, 1)),
                mapOf(13 to stages(1, 1)),
            )
        }
    }
}
