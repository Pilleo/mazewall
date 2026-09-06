package io.mazewall.profiler.attribution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TierEOptionsTest {
    @Test
    fun `full stream is the default and is the only full-trace guarantee`() {
        assertEquals(TierEEmissionMode.FULL_STREAM, TierEOptions().emissionMode)

        val full = SessionIntegrity(emissionMode = TierEEmissionMode.FULL_STREAM)
        assertEquals(CaptureGuarantee.FULL_STREAM, full.guarantee)
        assertTrue(full.complete)

        val unique = SessionIntegrity(emissionMode = TierEEmissionMode.UNIQUE_STACK_SYSCALL)
        assertEquals(CaptureGuarantee.UNIQUE_EDGES, unique.guarantee)
        assertFalse(unique.complete)
        assertTrue(unique.coverageComplete)
    }
}
