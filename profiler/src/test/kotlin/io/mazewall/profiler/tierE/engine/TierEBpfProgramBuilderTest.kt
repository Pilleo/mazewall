package io.mazewall.profiler.tierE.engine
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.attribution.TierEEmissionMode
import io.mazewall.profiler.internal.attribution.TierEOptions
import io.mazewall.profiler.internal.attribution.TierESysEnterAttachmentBackend
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import io.mazewall.profiler.tierE.ringbuf.SyscallInvocationEvent
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
    fun `active session without task storage records a dedicated diagnostic before ring reservation`() {
        val program = TierEBpfProgramBuilder.buildSysEnterProgram()
        val taskStorageGet = program.indexOfFirst { it.code == 0x85 && it.imm == 156 }
        val noStorage = program[taskStorageGet + 1]
        val noStorageTarget = taskStorageGet + 2 + noStorage.off

        assertEquals(0x15, noStorage.code, "task-storage lookup result must be checked")
        assertEquals(0x18, program[noStorageTarget].code, "missing task storage must enter the diagnostic path")
        assertEquals(2, program[noStorageTarget].imm, "task-storage diagnostics must use the kernel counter map")
        assertTrue(
            reachableInstructions(program, noStorageTarget).none { index -> program[index].code == 0x85 && program[index].imm == 131 },
            "missing task storage must exit before ring-buffer reservation",
        )
    }

    @Test
    fun `benchmark diagnostic mode emits a zero invocation record for unavailable task storage`() {
        val program = TierEBpfProgramBuilder.buildSysEnterProgram(
            TierEOptions(benchmarkPhases = true, benchmarkEmitTaskStorageUnavailableEvents = true),
        )
        val taskStorageGet = program.indexOfFirst { it.code == 0x85 && it.imm == 156 }
        val noStorage = program[taskStorageGet + 1]
        val noStorageTarget = taskStorageGet + 2 + noStorage.off

        assertEquals(0x18, program[noStorageTarget].code)
        assertEquals(2, program[noStorageTarget].imm, "diagnostic mode must retain the task-storage counter")
        assertTrue(
            reachableInstructions(program, noStorageTarget).any { index -> program[index].code == 0x85 && program[index].imm == 131 },
            "diagnostic mode must publish an owned zero-invocation ring record",
        )
        assertTrue(
            program.any {
                it.code == 0xb7 && it.dst == 5 && it.imm == 4
            },
            "diagnostic record must carry the task-storage-unavailable flag",
        )
    }

    @Test
    fun `benchmark marker-loss diagnostics preserve every causal branch in the ring record`() {
        val program = TierEBpfProgramBuilder.buildSysEnterProgram(
            TierEOptions(benchmarkPhases = true, benchmarkEmitMarkerLossEvents = true),
        )

        assertTrue(
            setOf(4, 8, 16, 32, 64).all { expectedFlag ->
                program.any { it.code == 0xb7 && it.dst == 5 && it.imm == expectedFlag }
            },
            "diagnostic records must distinguish unavailable storage, detached marker, and stale generation",
        )
        setOf(4, 8, 16, 32, 64).forEach { expectedFlag ->
            val diagnosticStart = program.indexOfFirst { it.code == 0xb7 && it.dst == 5 && it.imm == expectedFlag }
            assertTrue(
                reachableInstructions(program, diagnosticStart).any { index -> program[index].code == 0x85 && program[index].imm == 131 },
                "marker-loss flag $expectedFlag must reach the shared ring-record reservation path",
            )
        }
        assertEquals(
            program.indices.toSet(),
            reachableInstructions(program, start = 0),
            "all marker-loss diagnostic blocks must be reachable",
        )
    }

    @Test
    fun `all generated sys enter instructions are reachable for every supported diagnostic shape`() {
        listOf(
            TierEOptions(emissionMode = TierEEmissionMode.FULL_STREAM),
            TierEOptions(emissionMode = TierEEmissionMode.UNIQUE_STACK_SYSCALL),
            TierEOptions(benchmarkPhases = true, benchmarkEmitTaskStorageUnavailableEvents = true),
            TierEOptions(
                emissionMode = TierEEmissionMode.UNIQUE_STACK_SYSCALL,
                benchmarkPhases = true,
                benchmarkEmitTaskStorageUnavailableEvents = true,
            ),
        ).forEach { options ->
            val program = TierEBpfProgramBuilder.buildSysEnterProgram(options)
            assertEquals(
                program.indices.toSet(),
                reachableInstructions(program, start = 0),
                "every generated instruction must be reachable for $options",
            )
        }
    }

    @Test
    fun `missing diagnostic context still reserves an exact ring observation`() {
        val program = TierEBpfProgramBuilder.buildSysEnterProgram()
        val taskStorageGet = program.indexOfFirst { it.code == 0x85 && it.imm == 156 }
        val contextIdentityLoad =
            (taskStorageGet + 1 until program.size).first { index ->
                program[index].code == 0x79 && program[index].dst == 5 && program[index].off.toInt() == -24
            }
        val noMetricContext = program[contextIdentityLoad + 1]
        val noMetricContextTarget = contextIdentityLoad + 2 + noMetricContext.off

        assertEquals(0x15, noMetricContext.code, "diagnostic-context lookup result must be checked")
        assertEquals(0x18, program[noMetricContextTarget].code, "missing diagnostics must continue to ring reservation")
        assertEquals(1, program[noMetricContextTarget].imm, "missing diagnostics must use the ring-buffer map")
        assertTrue(
            reachableInstructions(program, noMetricContextTarget).any { index -> program[index].code == 0x85 && program[index].imm == 131 },
            "missing diagnostic context must not suppress exact ring observations",
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
    fun `raw tracepoint sys enter program preserves full record emission while reading x86 regs safely`() {
        TierEEmissionMode.entries.forEach { emissionMode ->
            val program = TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram(
                TierEOptions(emissionMode = emissionMode),
            )

            assertEquals(0x95, program.last().code)
            assertTrue(program.any { it.code == 0x79 && it.src == 9 && it.off.toInt() == 0 })
            assertTrue(
                program.count { it.code == 0x85 && it.imm == 113 } >= SyscallInvocationEvent.ARGUMENT_COUNT,
                "raw tracepoint arguments must be copied through the kernel-read helper",
            )
            assertEquals(
                SyscallInvocationEvent.ARGUMENT_COUNT,
                program.count { it.code == 0x7b && it.dst == 7 && it.off.toInt() in 40..80 },
                "raw tracepoint must retain the unchanged six-argument event payload",
            )
            assertEquals(program.indices.toSet(), reachableInstructions(program, 0))
        }
    }

    @Test
    fun `automatic attachment selects direct raw tracepoint only for its supported ABI`() {
        assertEquals(
            TierESysEnterAttachmentBackend.DIRECT_RAW_TRACEPOINT,
            TierEbpfEngine.selectSysEnterAttachmentBackend(TierESysEnterAttachmentBackend.AUTO, "x86_64"),
        )
        assertEquals(
            TierESysEnterAttachmentBackend.PERF_TRACEPOINT_COMPAT,
            TierEbpfEngine.selectSysEnterAttachmentBackend(TierESysEnterAttachmentBackend.AUTO, "aarch64"),
        )
        assertEquals(
            TierESysEnterAttachmentBackend.PERF_TRACEPOINT_COMPAT,
            TierEbpfEngine.selectSysEnterAttachmentBackend(TierESysEnterAttachmentBackend.PERF_TRACEPOINT_COMPAT, "x86_64"),
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

    @Test
    fun `ring reservation failure increments the loss counter rather than the exact admission counter`() {
        val program = TierEBpfProgramBuilder.buildSysEnterProgram()
        val reserve = program.indexOfFirst { it.code == 0x85 && it.imm == 131 }
        val reservationFailed = program[reserve + 1]
        val lossHandler = reserve + 2 + reservationFailed.off

        assertEquals(0x15, reservationFailed.code)
        assertTrue(
            program.drop(lossHandler).take(8).windowed(2).any { (first, second) ->
                first.code == 0xb7 &&
                    first.dst == 5 &&
                    first.imm == 0 &&
                    second.code == 0x63 &&
                    second.dst == 10 &&
                    second.src == 5 &&
                    second.off.toInt() == -4
            },
            "ring reservation failure must reset the counter-map key to loss slot zero",
        )
    }

    @Test
    fun `raw sys enter audit splits declared target openat family descriptors without ring submission`() {
        val program = TierEBpfProgramBuilder.buildRawSysEnterAuditProgram()

        assertEquals(0x95, program.last().code)
        assertTrue(program.any { it.code == 0x18 && it.src == 1 && it.imm == 0 }, "audit must load its descriptor-count map")
        assertTrue(program.any { it.code == 0x18 && it.src == 1 && it.imm == 1 }, "audit must load the session map")
        assertTrue(program.any { it.code == 0x61 && it.src == 9 && it.off.toInt() == 8 }, "audit must read the raw syscall id")
        assertTrue(program.any { it.code == 0x15 && it.imm == 257 }, "audit must admit openat")
        assertTrue(program.any { it.code == 0x55 && it.imm == 437 }, "audit must reject every syscall outside the openat family")
        assertTrue(program.any { it.code == 0x79 && it.src == 9 && it.off.toInt() == 16 }, "audit must key by openat directory descriptor")
        assertEquals(setOf(0, 8), program.filter { it.code == 0xdb && it.dst == 0 }.map { it.off.toInt() }.toSet(), "audit must retain separate openat and openat2 counters")
        assertTrue(program.none { it.code == 0x85 && it.imm in setOf(131, 132) }, "audit must never reserve or submit ring records")
        assertEquals(program.indices.toSet(), reachableInstructions(program, 0), "every audit instruction must be reachable")
    }

    @Test
    fun `tracepoint attachment covers every allowed online cpu including non contiguous sets`() {
        assertEquals(
            listOf(0, 2),
            TierEbpfEngine.selectTraceAttachmentCpus(onlineCpuList = "0-3", allowedCpuList = "0,2"),
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
