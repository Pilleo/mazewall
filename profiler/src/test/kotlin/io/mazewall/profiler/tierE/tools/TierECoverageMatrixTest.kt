package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TierECoverageMatrixTest {
    @Test
    fun `long forks start with baseline and rotate six variants by seed`() {
        val arms = TierECoverageMatrix.plan(2, longMatched = true, variants = "hybrid")
        assertEquals(14, arms.size)
        assertEquals("baseline", arms[0].name)
        assertEquals(23063, arms[0].seed)
        assertEquals("dictionary-unique-edge-hybrid", arms[1].name)
        assertEquals("baseline", arms[7].name)
        assertEquals("dictionary-full-stream-v1", arms[8].name)
        assertThrows(IllegalArgumentException::class.java) { TierECoverageMatrix.plan(0, true, "hybrid") }
        assertThrows(IllegalArgumentException::class.java) { TierECoverageMatrix.plan(1, false, "unexpected") }
    }

    @Test
    fun `short legacy coverage keeps variant-major order and fork seeds`() {
        val arms = TierECoverageMatrix.plan(2, longMatched = false, variants = "legacy")
        assertEquals(8, arms.size)
        assertEquals(
            listOf("dictionary-full-stream", "dictionary-full-stream", "dictionary-unique-edge", "dictionary-unique-edge"),
            arms.take(4).map { it.name },
        )
        assertEquals(listOf(23063, 23064), arms.take(2).map { it.seed })
    }
}
