package io.mazewall.profiler.tierE.engine
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.attribution.TierEEmissionMode
import io.mazewall.profiler.internal.attribution.TierEOptions
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
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

    @Test
    fun `unique stack mode does not suppress repeated ring observations in the kernel`() {
        val fullStreamProg = TierEBpfProgramBuilder.buildSysEnterProgram(
            TierEOptions(emissionMode = TierEEmissionMode.FULL_STREAM),
        )
        val uniqueStackProg = TierEBpfProgramBuilder.buildSysEnterProgram(
            TierEOptions(emissionMode = TierEEmissionMode.UNIQUE_STACK_SYSCALL),
        )

        assertEquals(
            fullStreamProg.count { it.code == 0x85 && it.imm == 132 },
            uniqueStackProg.count { it.code == 0x85 && it.imm == 132 },
        )
    }

    @Test
    fun `sys enter program reserves a bounded per-context metric map slot`() {
        val program = TierEBpfProgramBuilder.buildSysEnterProgram()

        assertTrue(
            program.any { it.code == 0x18 && it.src == 1 && it.imm == 5 },
            "program must reference the dedicated per-context metrics map",
        )
    }
}
