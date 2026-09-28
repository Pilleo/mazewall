package io.mazewall.profiler.tierE.stress

import io.mazewall.profiler.tierE.engine.TierEBenchmarkAuditMapReader.InvocationWitnessCount
import io.mazewall.profiler.tierE.engine.TierEPreGateAuditCount
import io.mazewall.profiler.tierE.engine.TierEbpfEngine.EarlyTargetOpenatCounts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TierEInvocationWitnessConservationTest {
    private val native = mapOf(13 to 0x4d5a0000002aL, 14 to 0x4d5a0000002bL)
    private val exact = mapOf(13 to TierEPreGateAuditCount(1, 1, 1, 1, 1), 14 to TierEPreGateAuditCount(1, 1, 1, 1, 1))
    private val tokens = native.values.associateWith { InvocationWitnessCount(1, 1) }
    private val failures = (9..14).associateWith { if (it == 11 || it == 12) 3L else 0L }

    @Test
    fun `surplus zero-token calls may be classified only after every declared ID fully joins`() {
        val result = TierEInvocationWitnessConservation.evaluate(
            native,
            tokens,
            exact,
            mapOf(13 to 1L, 14 to 1L),
            mapOf(13 to 1L, 14 to 1L),
            EarlyTargetOpenatCounts(main = 5, raw = 5),
            failures,
        )
        assertTrue(result.complete)
        assertEquals(3L, result.ambientRaw)
        assertEquals(3L, result.ambientMain)
    }

    @Test
    fun `missing declared token cannot be excused by surplus ambient calls`() {
        val result = TierEInvocationWitnessConservation.evaluate(
            native,
            tokens + (native.getValue(14) to InvocationWitnessCount(0, 1)),
            exact + (14 to TierEPreGateAuditCount(1, 0, 1, 0, 1)),
            mapOf(13 to 1L, 14 to 0L),
            mapOf(13 to 1L, 14 to 1L),
            EarlyTargetOpenatCounts(main = 5, raw = 4),
            failures,
        )
        assertFalse(result.complete)
    }

    @Test
    fun `unknown marker ID or map failure invalidates conservation`() {
        val extra = tokens + (0x4d5a0000002cL to InvocationWitnessCount(1, 1))
        val invalid = TierEInvocationWitnessConservation.evaluate(
            native,
            extra,
            exact,
            mapOf(13 to 1L, 14 to 1L),
            mapOf(13 to 1L, 14 to 1L),
            EarlyTargetOpenatCounts(5, 5),
            failures,
        )
        assertFalse(invalid.complete)
        val mapFailure = TierEInvocationWitnessConservation.evaluate(
            native,
            tokens,
            exact,
            mapOf(13 to 1L, 14 to 1L),
            mapOf(13 to 1L, 14 to 1L),
            EarlyTargetOpenatCounts(5, 5),
            failures + (13 to 1L),
        )
        assertFalse(mapFailure.complete)
    }
}
