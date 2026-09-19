package io.mazewall.profiler.tierE.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TierEProcessExecProgramTest {
    @Test
    fun `successful exec program clears every task marker field`() {
        val program = TierEBpfProgramBuilder.buildProcessExecProgram()

        assertEquals(0x95, program.last().code)
        val clearOffsets = program
            .filter { it.dst == 0 && it.src == 5 && it.code in setOf(0x7b, 0x63) }
            .map { it.off.toInt() }
            .toSet()
        assertEquals(setOf(0, 8, 16, 20), clearOffsets)
        assertTrue(program.any { it.code == 0x85 && it.imm == 156 })
    }
}
