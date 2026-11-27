package io.mazewall.profiler.tierE.engine
import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.ffi.memory.native
import io.mazewall.platform.lifecycle.testAcquisition
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
import io.mazewall.profiler.internal.attribution.TierESysEnterAttachmentBackend
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import java.lang.foreign.Arena
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BpfProgLoadLayoutTest {
    @org.junit.jupiter.api.io.TempDir
    lateinit var evidenceDirectory: java.nio.file.Path

    @Test
    @EnabledIfLinuxAndSupported
    fun `diagnostic failed load names actual program and preserves native error and rollback`() {
        for ((index, name) in listOf("tier_e_marker", "tier_e_exec", "tier_e_syscall").withIndex()) {
            val native = FailedLoadNative(index + 1)
            var captured = 0
            val engine = TierEbpfEngine(
                native,
                BoundaryDiagnosticConfig(
                    java.nio.file.Path
                .of("/fixture/evidence/boundary.bpfdiag"),
                ),
                    { fd -> native.info.getValue(fd) },
                { capture ->
                captured++
                assertEquals(index + 1, capture.ordinal)
                assertEquals(name, capture.name)
                assertEquals(listOf(398, 31, 647)[index] * 8L, capture.request.instructions.byteSize())
                assertEquals(if (index == 0) 2 else 17, capture.type)
                assertEquals(7L, capture.installation)
                assertEquals(42, capture.target)
                assertEquals(19802, capture.session)
                TierEBpfProgramLoadFailureEvidence.write(capture, evidenceDirectory.resolve(name))
            },
            )
            engine.boundaryControlReporter(7) {}
            val failure = assertFailsWith<IllegalStateException> {
                engine.install(42, 19802, TierEOptions(allTargetCapture = true, sysEnterAttachmentBackend = TierESysEnterAttachmentBackend.DIRECT_RAW_TRACEPOINT))
            }
            assertTrue(failure.message.orEmpty().contains(name), failure.message)
            assertTrue(failure.message.orEmpty().contains("ordinal=${index + 1}"), failure.message)
            assertTrue(failure.message.orEmpty().contains("errno=28"), failure.message)
            assertEquals(index + 1, native.loads)
            assertEquals(1, captured)
            assertEquals(List(index + 1) { 144L }, native.loadSizes)
            assertEquals(native.acquired.toSet(), native.closed.toSet())
            assertEquals(native.acquired.size, native.closed.size)
            engine.close()
            assertEquals(native.acquired.size, native.closed.size)
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `evidence failure preserves original error unless identical fatal error wins and rollback still retires`() {
        for (fatal in listOf(false, true)) {
            val native = FailedLoadNative(3)
            val publication = if (fatal) LinkageError("publication") else java.io.IOException("publication")
            val engine = TierEbpfEngine(
                native,
                BoundaryDiagnosticConfig(
                    java.nio.file.Path
                .of("/fixture/evidence/boundary.bpfdiag"),
                ),
                    { native.info.getValue(it) },
                { throw publication },
            )
            engine.boundaryControlReporter(7) {}
            val thrown = assertFailsWith<Throwable> {
                engine.install(42, 19802, TierEOptions(allTargetCapture = true, sysEnterAttachmentBackend = TierESysEnterAttachmentBackend.DIRECT_RAW_TRACEPOINT))
            }
            if (fatal) {
                assertSame(publication, thrown)
                assertTrue(
                    thrown.suppressed
                    .single()
                    .message
                    .orEmpty()
                    .contains("program=tier_e_syscall ordinal=3"),
                )
            } else {
                assertTrue(thrown is IllegalStateException)
                assertSame(publication, thrown.suppressed.single())
            }
            assertEquals(native.acquired.sorted(), native.closed.sorted())
            engine.close()
            assertEquals(native.acquired.size, native.closed.size)
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `successful loads adopt real descriptors without evidence publication and disabled request stays short`() {
        for (enabled in listOf(false, true)) {
            val native = FailedLoadNative(0)
            val engine =
                TierEbpfEngine(
                    native,
                    if (enabled) {
                    BoundaryDiagnosticConfig(
                        java.nio.file.Path
                    .of("/fixture/evidence/boundary.bpfdiag"),
                    )
                } else {
                    null
                },
                    {
                    native.info.getValue(it)
                },
                    { error("success must not publish failure evidence") },
                )
            if (enabled) engine.boundaryControlReporter(7) {}
            engine.install(42, 19802, TierEOptions(allTargetCapture = true, sysEnterAttachmentBackend = TierESysEnterAttachmentBackend.DIRECT_RAW_TRACEPOINT))
            assertEquals(List(3) { if (enabled) 144L else 104L }, native.loadSizes)
            assertTrue(native.closed.isEmpty())
            engine.close()
            assertEquals(native.acquired.sorted(), native.closed.sorted())
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `diagnostic request extends same native call with a zero tail`() {
        val native = FailedLoadNative(1)
        var captured = 0
        val engine = TierEbpfEngine(
            native,
            BoundaryDiagnosticConfig(
                java.nio.file.Path
            .of("/fixture/evidence/boundary.bpfdiag"),
            ),
                { fd -> native.info.getValue(fd) },
            { captured++ },
        )
        engine.boundaryControlReporter(7) {}
        assertFailsWith<IllegalStateException> {
            engine.install(42, 19802, TierEOptions(allTargetCapture = true, sysEnterAttachmentBackend = TierESysEnterAttachmentBackend.DIRECT_RAW_TRACEPOINT))
        }
        assertEquals(1, captured)
        assertEquals(listOf(144L), native.loadSizes)
        assertEquals(List(40) { 0.toByte() }, native.tail)
    }

    private class FailedLoadNative(
        private val failAt: Int,
    ) : MockNativeEngine() {
        val acquired = mutableListOf<Long>()
        val closed = mutableListOf<Long>()
        val info = mutableMapOf<Int, String>()
        val loadSizes = mutableListOf<Long>()
        var tail = emptyList<Byte>()
        var loads = 0

        override fun syscall(
            nr: Long,
            a1: NativeArg,
            a2: NativeArg,
            a3: NativeArg,
            a4: NativeArg,
            a5: NativeArg,
            a6: NativeArg,
        ): SyscallResult<Long, SyscallHandledState.Unhandled> {
            if (nr == Arch.current().close.toLong()) {
                closed += a1.asLong
                return io.mazewall.ffi.internal.RealNativeEngine
                    .syscall(nr, a1, a2, a3, a4, a5, a6)
            }
            check(nr == Syscall.BPF.numberFor(Arch.current()).toLong())
            val command = a1.asLong
            val attr = (a2 as NativeArg.MemoryArg).value.native
            if (command == 1L) attr.get(java.lang.foreign.ValueLayout.ADDRESS, 16).reinterpret(128).fill(0)
            if (command == 5L) {
                loads++
                loadSizes += a3.asLong
                tail = (104L until a3.asLong).map { attr.get(java.lang.foreign.ValueLayout.JAVA_BYTE, it) }
                if (loads == failAt) return SyscallResult.Error(28, -1)
            }
            if (command !in listOf(0L, 5L, 17L, 18L)) return SyscallResult.Success(0)
            return io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
                io.mazewall.platform.native.LinuxNative.fileSystem
                    .open(
                    arena.allocateFrom("/dev/null"),
                    io.mazewall.platform.lifecycle
                    .OpenFlags(0),
                ).also { result ->
                    if (result is SyscallResult.Success) {
                        acquired += result.value
                        if (command == 0L) {
                            val size = attr.get(java.lang.foreign.ValueLayout.JAVA_INT, 8)
                            val capacity = attr.get(java.lang.foreign.ValueLayout.JAVA_INT, 12)
                            val type = attr.get(java.lang.foreign.ValueLayout.JAVA_INT, 0)
                            info[result.value.toInt()] = "map_type: $type\nvalue_size: $size\nmax_entries: $capacity\nmemlock: ${if (size == 112) 3674112 else 4096}\n"
                        }
                    }
                }
            }
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `native close promotes fatal identity after unmap failure and retries only failed mapping`() {
        Arena.ofShared().use { arena ->
            val native = RetirementNative(arena)
            val engine = TierEbpfEngine(native)
            val ordinary = IllegalStateException("unmap failed")
            val fatal = LinkageError("native descriptor close failed")
            try {
                engine.install(42, 1, TierEOptions(sysEnterAttachmentBackend = TierESysEnterAttachmentBackend.DIRECT_RAW_TRACEPOINT))
                engine.drainEvents()
                native.unmapFailure = ordinary
                native.closeFailure = fatal

                assertSame(fatal, assertFailsWith<LinkageError> { engine.close() })
                assertEquals(listOf(ordinary), fatal.suppressed.toList())
                assertEquals(native.acquired.toSet(), native.closed.toSet(), "independent descriptors must all retire")
                assertEquals(native.acquired.size, native.closed.size, "each owned descriptor gets one raw close attempt")
                val released = native.closed.toList()
                engine.close()
                engine.close()
                assertEquals(released, native.closed, "raw close must never be retried, even after a thrown Error")
                assertEquals(
                    listOf(1, 2),
                    native.unmapped
                    .groupingBy { it }
                    .eachCount()
                    .values
                    .sorted(),
                )
            } finally {
                engine.close()
            }
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `native acquisition rollback promotes identical fatal cleanup over original acquisition error`() {
        Arena.ofShared().use { arena ->
            val native = RetirementNative(arena)
            val ordinary = IllegalStateException("map acquisition failed")
            val fatal = LinkageError("rollback descriptor close failed")
            native.acquisitionFailure = ordinary
            native.closeFailure = fatal
            val engine = TierEbpfEngine(native)

            assertSame(fatal, assertFailsWith<LinkageError> { engine.install(42) })
            assertEquals(listOf(ordinary), fatal.suppressed.toList())
            assertEquals(native.acquired, native.closed)
            engine.close()
            assertEquals(native.acquired, native.closed, "rollback must clear raw descriptors after failure")
        }
    }

    private class RetirementNative(
        private val arena: Arena,
    ) : MockNativeEngine() {
        val acquired = mutableListOf<Long>()
        val closed = mutableListOf<Long>()
        val unmapped = mutableListOf<Long>()
        var unmapFailure: Throwable? = null
        var closeFailure: Throwable? = null
        var acquisitionFailure: Throwable? = null

        override fun syscall(
            nr: Long,
            a1: NativeArg,
            a2: NativeArg,
            a3: NativeArg,
            a4: NativeArg,
            a5: NativeArg,
            a6: NativeArg,
        ): SyscallResult<Long, SyscallHandledState.Unhandled> =
            when (nr) {
            Syscall.BPF.numberFor(Arch.current()).toLong() -> {
                if (acquired.isNotEmpty()) acquisitionFailure?.let { throw it }
                val value = when (a1.asLong) {
                    0L, 5L, 17L, 18L -> (71L + acquired.size).also(acquired::add)
                    else -> 0L
                }
                val result = SyscallResult.Success<Long, SyscallHandledState.Unhandled>(value)
                if (a1.asLong == 0L) result.testAcquisition() else result
            }
            Syscall.MMAP.numberFor(Arch.current()).toLong() -> SyscallResult.Success(arena.allocate(a2.asLong, 4096).address())
            Syscall.MUNMAP.numberFor(Arch.current()).toLong() -> {
                unmapped += a1.asLong
                unmapFailure?.let {
                    unmapFailure = null
                    throw it
                }
                SyscallResult.Success(0L)
            }
            Arch.current().close.toLong() -> {
                closed += a1.asLong
                closeFailure?.let {
                    closeFailure = null
                    throw it
                }
                SyscallResult.Success(0L)
            }
            else -> error("unexpected native syscall $nr")
        }
    }

    @Test
    fun `opt in capture gate extends only the Tier E session map value`() {
        assertEquals(16, TierEbpfEngine.sessionConfigurationSize(TierEOptions()))
        assertEquals(24, TierEbpfEngine.sessionConfigurationSize(TierEOptions(allTargetCapture = true)))
    }

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
                            1 -> SyscallResult.Success<Long, SyscallHandledState.Unhandled>(71L).testAcquisition()
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
                        1 -> SyscallResult.Success<Long, SyscallHandledState.Unhandled>(71L).testAcquisition()
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

    @Test
    fun `program-info query layouts match the stable linux uapi prefix`() {
        assertEquals(16L, BpfObjGetInfoLayout.SIZE)
        assertEquals(0L, BpfObjGetInfoLayout.BPF_FD)
        assertEquals(4L, BpfObjGetInfoLayout.INFO_LEN)
        assertEquals(8L, BpfObjGetInfoLayout.INFO)
        assertEquals(4L, BpfProgramInfoLayout.ID)
        assertEquals(20L, BpfProgramInfoLayout.XLATED_PROGRAM_LENGTH)
        assertEquals(192L, BpfProgramInfoLayout.RUN_TIME_NANOS)
        assertEquals(200L, BpfProgramInfoLayout.RUN_COUNT)
        assertEquals(208L, BpfProgramInfoLayout.RECURSION_MISSES)
        assertEquals(232L, BpfProgramInfoLayout.SIZE)
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
