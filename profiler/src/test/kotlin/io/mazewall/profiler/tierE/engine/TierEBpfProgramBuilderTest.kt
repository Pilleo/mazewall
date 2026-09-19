package io.mazewall.profiler.tierE.engine
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.attribution.TierEEmissionMode
import io.mazewall.profiler.internal.attribution.TierEOptions
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import org.junit.jupiter.api.Test
import java.util.ArrayDeque
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

    @Test
    fun `active session without task storage records an ambient diagnostic before ring reservation`() {
        val program = TierEBpfProgramBuilder.buildSysEnterProgram()
        val taskStorageGet = program.indexOfFirst { it.code == 0x85 && it.imm == 156 }
        val noStorage = program[taskStorageGet + 1]
        val noStorageTarget = taskStorageGet + 2 + noStorage.off

        assertEquals(0x15, noStorage.code, "task-storage lookup result must be checked")
        assertEquals(0x18, program[noStorageTarget].code, "missing task storage must enter the diagnostic path")
        assertEquals(2, program[noStorageTarget].imm, "ambient diagnostics must use the kernel counter map")
        assertTrue(
            reachableInstructions(program, noStorageTarget).none { index -> program[index].code == 0x85 && program[index].imm == 131 },
            "ambient syscalls must exit before ring-buffer reservation",
        )
    }

    @Test
    fun `sys enter program reads syscall arguments directly from the typed tracepoint context`() {
        val program = TierEBpfProgramBuilder.buildSysEnterProgram()

        assertTrue(
            program.none { it.code == 0x85 && it.imm == 113 },
            "raw_syscalls/sys_enter supplies id and args directly; pt_regs probe reads must not be used",
        )
        assertTrue(
            program.any { it.code == 0x79 && it.src == 9 && it.off.toInt() == 16 },
            "program must read the first syscall argument from raw_syscalls/sys_enter args[0]",
        )
    }

    @Test
    fun `marker attachment generation is stored and required before syscall emission`() {
        val marker = TierEBpfProgramBuilder.buildMarkerProgram()
        val sysEnter = TierEBpfProgramBuilder.buildSysEnterProgram()

        assertTrue(
            marker.any { it.code == 0x63 && it.off.toInt() == 20 },
            "marker state must retain its attachment generation in task-storage padding",
        )
        assertTrue(
            sysEnter.any { it.code == 0x61 && it.off.toInt() == 20 },
            "sys_enter must load the task-storage attachment generation",
        )
    }

    @Test
    fun `admitted sys enter paths use the dedicated in flight counter`() {
        val program = TierEBpfProgramBuilder.buildSysEnterProgram()

        assertEquals(
            2,
            program.count { it.code == 0x18 && it.src == 1 && it.imm == 6 },
            "admission and shared completion must each load the in-flight map",
        )
        assertTrue(
            program.count { it.code == 0xdb } >= 3,
            "in-flight admission and completion must both be atomic",
        )
    }

    private fun reachableInstructions(
        program: List<TierEbpfEngine.Insn>,
        start: Int,
    ): Set<Int> {
        val pending = ArrayDeque<Int>()
        val reached = mutableSetOf<Int>()
        pending += start
        while (pending.isNotEmpty()) {
            val index = pending.removeFirst()
            if (index !in program.indices || !reached.add(index)) continue
            val instruction = program[index]
            val fallthrough = index + 1
            when (instruction.code) {
                0x95 -> Unit
                0x05 -> pending += fallthrough + instruction.off
                0x15, 0x5d -> {
                    pending += fallthrough
                    pending += fallthrough + instruction.off
                }
                else -> pending += fallthrough
            }
        }
        return reached
    }
}
