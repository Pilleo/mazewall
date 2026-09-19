package io.mazewall.profiler.tierE.engine
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative.SyscallHandledState
import io.mazewall.platform.native.LinuxNative.SyscallResult
import io.mazewall.platform.native.RawSyscallOperations
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.attribution.TierEEmissionMode
import io.mazewall.profiler.internal.attribution.TierEOptions
import io.mazewall.profiler.internal.attribution.TierERingSubmissionMode
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BpfProgLoadLayoutTest {
    @Test
    fun `failed install closes every already acquired descriptor`() {
        var bpfCalls = 0
        val closed = mutableListOf<Long>()
        val native = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(RawSyscallOperations::class.java),
        ) { _, method, args ->
            when (method.name) {
                "syscall" -> {
                    val number = args[0] as Long
                    when (number) {
                        Syscall.BPF.numberFor(Arch.current()).toLong() -> when (++bpfCalls) {
                            1 -> SyscallResult.Success<Long, SyscallHandledState.Unhandled>(71L)
                            else -> SyscallResult.Error<SyscallHandledState.Unhandled>(5, -1)
                        }
                        Arch.current().close.toLong() -> {
                            closed += (args[1] as io.mazewall.platform.syscall.NativeArg.LongArg).value
                            SyscallResult.Success<Long, SyscallHandledState.Unhandled>(0L)
                        }
                        else -> error("unexpected syscall $number")
                    }
                }
                else -> error("unexpected raw operation ${method.name}")
            }
        } as RawSyscallOperations

        assertFailsWith<IllegalStateException> { TierEbpfEngine(native).install(42) }

        assertEquals(listOf(71L), closed)
    }

    @Test
    fun `failed install preserves a descriptor cleanup failure`() {
        var bpfCalls = 0
        val native = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(RawSyscallOperations::class.java),
        ) { _, method, args ->
            when (method.name) {
                "syscall" -> when (args[0] as Long) {
                    Syscall.BPF.numberFor(Arch.current()).toLong() -> when (++bpfCalls) {
                        1 -> SyscallResult.Success<Long, SyscallHandledState.Unhandled>(71L)
                        else -> SyscallResult.Error<SyscallHandledState.Unhandled>(5, -1L)
                    }
                    Arch.current().close.toLong() -> SyscallResult.Error<SyscallHandledState.Unhandled>(5, -1L)
                    else -> error("unexpected syscall ${args[0]}")
                }
                else -> error("unexpected raw operation ${method.name}")
            }
        } as RawSyscallOperations

        val failure = assertFailsWith<IllegalStateException> { TierEbpfEngine(native).install(42) }

        assertEquals(1, failure.suppressed.size)
    }

    @Test
    fun `uses the linux bpf prog load attribute offsets`() {
        assertEquals(16L, BpfProgLoadLayout.LICENSE)
        assertEquals(24L, BpfProgLoadLayout.LOG_LEVEL)
        assertEquals(28L, BpfProgLoadLayout.LOG_SIZE)
        assertEquals(32L, BpfProgLoadLayout.LOG_BUFFER)
        assertEquals(48L, BpfProgLoadLayout.PROGRAM_NAME)
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
        assertEquals(8L, PerfEventAttrLayout.CONFIG)
        assertEquals(16L, PerfEventAttrLayout.SAMPLE_PERIOD)
        assertEquals(56L, PerfEventAttrLayout.UPROBE_PATH)
        assertEquals(64L, PerfEventAttrLayout.UPROBE_OFFSET)
    }

    @Test
    fun `all generated branches are reachable and stay inside the program`() {
        val unusedNative = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(RawSyscallOperations::class.java),
        ) { _, _, _ -> error("buildProgram must not make syscalls") } as RawSyscallOperations
        val fullStream = TierEbpfEngine(unusedNative).buildProgram()
        val uniqueCoverage = TierEbpfEngine(unusedNative).buildProgram(
            TierEOptions(TierEEmissionMode.UNIQUE_STACK_SYSCALL),
        )
        val noRingControl = TierEbpfEngine(unusedNative).buildProgram(
            TierEOptions(
                benchmarkPhases = true,
                ringSubmissionMode = TierERingSubmissionMode.BENCHMARK_DISABLED,
            ),
        )

        assertVerifierReachable(fullStream)
        assertVerifierReachable(uniqueCoverage)
        assertVerifierReachable(noRingControl)
        assertTrue(noRingControl.size < fullStream.size, "no-ring control should omit ring payload construction")
        assertTrue(fullStream.none { it.code == 0x85 && it.imm == 113 }, "typed tracepoint arguments must not use pt_regs probe reads")
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

        assertEquals(1, fullStream.count { it.code == 0x85 && it.imm == 2 }, "full-stream must update only metrics")
        assertEquals(2, uniqueCoverage.count { it.code == 0x85 && it.imm == 2 }, "unique mode adds one coverage update")
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

    private fun assertVerifierReachable(program: List<TierEbpfEngine.Insn>) {
        val reachable = mutableSetOf<Int>()
        val pending = ArrayDeque<Int>()

        fun admit(index: Int) {
            assertTrue(index in program.indices, "control flow targets $index outside program")
            if (reachable.add(index)) pending.addLast(index)
        }

        admit(0)
        while (pending.isNotEmpty()) {
            val index = pending.removeFirst()
            val instruction = program[index]
            when (instruction.code) {
                0x95 -> Unit
                0x05 -> {
                    val target = index + instruction.off + 1
                    assertTrue(program[target].code != 0, "jump at $index targets an LD_MAP_FD continuation")
                    admit(target)
                }
                0x15, 0x5d -> {
                    val target = index + instruction.off + 1
                    assertTrue(program[target].code != 0, "branch at $index targets an LD_MAP_FD continuation")
                    admit(target)
                    admit(index + 1)
                }
                else -> if (index != program.lastIndex) admit(index + 1)
            }
        }

        assertEquals(program.indices.toSet(), reachable, "generated program contains verifier-unreachable instructions")
    }
}
