package io.mazewall.profiler.tierE.engine

import io.mazewall.profiler.attribution.TierEEmissionMode
import io.mazewall.profiler.attribution.TierEOptions
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TierEBpfProgramBuilderTest {
    @Test
    fun `buildMarkerProgram generates valid instruction sequence ending with exit`() {
        val program = TierEBpfProgramBuilder.buildMarkerProgram()
        assertTrue(program.isNotEmpty())
        assertEquals(0x95, program.last().code, "Program must end with EXIT opcode")
        assertTrue(program.size > 20, "Marker program should have instructions for map lookups and marker store")
    }

    @Test
    fun `buildSysEnterProgram generates valid instruction sequence for full stream and unique stack modes`() {
        val fullStreamProg = TierEBpfProgramBuilder.buildSysEnterProgram(TierEOptions(emissionMode = TierEEmissionMode.FULL_STREAM))
        val uniqueStackProg = TierEBpfProgramBuilder.buildSysEnterProgram(TierEOptions(emissionMode = TierEEmissionMode.UNIQUE_STACK_SYSCALL))

        assertTrue(fullStreamProg.isNotEmpty())
        assertTrue(uniqueStackProg.isNotEmpty())
        assertEquals(0x95, fullStreamProg.last().code, "Full stream program must end with EXIT opcode")
        assertEquals(0x95, uniqueStackProg.last().code, "Unique stack program must end with EXIT opcode")
        assertTrue(uniqueStackProg.size > fullStreamProg.size, "Unique stack program must include coverage cache lookup and update logic")
    }
}
