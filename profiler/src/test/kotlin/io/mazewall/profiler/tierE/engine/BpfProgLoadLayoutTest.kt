package io.mazewall.profiler.tierE.engine

import io.mazewall.RawSyscallOperations
import io.mazewall.profiler.attribution.TierEEmissionMode
import io.mazewall.profiler.attribution.TierEOptions
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BpfProgLoadLayoutTest {
    @Test
    fun `uses the linux bpf prog load attribute offsets`() {
        assertEquals(16L, BpfProgLoadLayout.LICENSE)
        assertEquals(24L, BpfProgLoadLayout.LOG_LEVEL)
        assertEquals(28L, BpfProgLoadLayout.LOG_SIZE)
        assertEquals(32L, BpfProgLoadLayout.LOG_BUFFER)
        assertEquals(48L, BpfProgLoadLayout.PROGRAM_NAME)
    }

    @Test
    fun `uses the linux raw tracepoint open attribute offsets`() {
        assertEquals(0L, BpfRawTracepointOpenLayout.NAME)
        assertEquals(8L, BpfRawTracepointOpenLayout.PROGRAM_FD)
    }

    @Test
    fun `uses the linux btf load and typed map attribute offsets`() {
        assertEquals(0L, BpfBtfLoadLayout.BTF)
        assertEquals(8L, BpfBtfLoadLayout.LOG_BUFFER)
        assertEquals(16L, BpfBtfLoadLayout.BTF_SIZE)
        assertEquals(20L, BpfBtfLoadLayout.LOG_SIZE)
        assertEquals(24L, BpfBtfLoadLayout.LOG_LEVEL)
        assertEquals(48L, BpfMapCreateLayout.BTF_FD)
        assertEquals(52L, BpfMapCreateLayout.BTF_KEY_TYPE_ID)
        assertEquals(56L, BpfMapCreateLayout.BTF_VALUE_TYPE_ID)
    }

    @Test
    fun `uses the linux perf event uprobe attribute offsets`() {
        assertEquals(0L, PerfEventAttrLayout.TYPE)
        assertEquals(4L, PerfEventAttrLayout.SIZE_FIELD)
        assertEquals(16L, PerfEventAttrLayout.SAMPLE_PERIOD)
        assertEquals(56L, PerfEventAttrLayout.UPROBE_PATH)
        assertEquals(64L, PerfEventAttrLayout.UPROBE_OFFSET)
    }

    @Test
    fun `all generated branches stay inside the program and filters exit`() {
        val unusedNative = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(RawSyscallOperations::class.java),
        ) { _, _, _ -> error("buildProgram must not make syscalls") } as RawSyscallOperations
        val program = TierEbpfEngine(unusedNative).buildProgram()
        val exitIndex = program.lastIndex

        program.forEachIndexed { index, instruction ->
            if (instruction.code == 0x15 || instruction.code == 0x5d) {
                val target = index + instruction.off + 1
                assertTrue(target in program.indices, "branch at $index targets $target outside program")
                if (instruction.code == 0x5d) {
                    assertEquals(exitIndex, target, "early exit branch at $index must target exit")
                }
            }
        }
        assertTrue(program.any { it.code == 0x85 && it.imm == 113 }, "syscall arguments must use probe_read_kernel")
    }

    @Test
    fun `unique coverage program has an independent guarded cache update`() {
        val unusedNative = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(RawSyscallOperations::class.java),
        ) { _, _, _ -> error("buildProgram must not make syscalls") } as RawSyscallOperations

        val fullStream = TierEbpfEngine(unusedNative).buildProgram()
        val uniqueCoverage = TierEbpfEngine(unusedNative).buildProgram(
            TierEOptions(TierEEmissionMode.UNIQUE_STACK_SYSCALL),
        )

        assertTrue(fullStream.none { it.code == 0x85 && it.imm == 2 }, "full-stream must not update a coverage cache")
        assertEquals(1, uniqueCoverage.count { it.code == 0x85 && it.imm == 2 })
        uniqueCoverage.forEachIndexed { index, instruction ->
            if (instruction.code == 0x15 || instruction.code == 0x05 || instruction.code == 0x5d) {
                val target = index + instruction.off + 1
                assertTrue(target in uniqueCoverage.indices, "branch at $index targets $target outside program")
            }
        }
    }

    @Test
    fun `marker program reads x86 first argument and updates task storage`() {
        val unusedNative = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(RawSyscallOperations::class.java),
        ) { _, _, _ -> error("program construction must not make syscalls") } as RawSyscallOperations

        val program = TierEbpfEngine(unusedNative).buildMarkerProgram()

        assertTrue(program.any { it.code == 0x79 && it.off.toInt() == 112 }, "marker must read pt_regs di")
        assertTrue(program.any { it.code == 0x85 && it.imm == 158 }, "marker must get current task BTF pointer")
        assertTrue(program.any { it.code == 0x85 && it.imm == 156 }, "marker must create/read task storage")
    }
}
