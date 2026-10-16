package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TierEMatchedMatrixTest {
    @Test
    fun `rotation keeps all seven canonical arms paired within each fork`() {
        val modes = TierEMatchedMatrix.defaultModes
        assertEquals(modes.map { 0 to it }, TierEMatchedMatrix.plan(1, modes))
        assertEquals(1 to "dictionary-full-stream", TierEMatchedMatrix.plan(2, modes)[7])
    }

    @Test
    fun `invalid and duplicate variants are rejected before scheduling`() {
        for (modes in listOf(
            listOf("dictionary-bogus-v2"),
            listOf("dictionary-full-stream", "dictionary-full-stream"),
            listOf("inline-full-stream-hybrid"),
        )) {
            assertThrows(IllegalArgumentException::class.java) { TierEMatchedMatrix.plan(1, modes) }
        }
    }
}
