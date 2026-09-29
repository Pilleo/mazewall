package io.mazewall.profiler.tierE.engine
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.attribution.CoverageRecoveryMode
import io.mazewall.profiler.internal.attribution.MarkerVersion
import io.mazewall.profiler.internal.attribution.TierEEmissionMode
import io.mazewall.profiler.internal.attribution.TierEKernelCoverageMode
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
    fun `marker v1 preserves existing task storage layout`() {
        val v1 = TierEBpfProgramBuilder.buildMarkerProgram(TierEOptions())
        val default = TierEBpfProgramBuilder.buildMarkerProgram()
        assertEquals(default.size, v1.size)
        assertTrue(v1.none { it.code == 0x79 && it.off == 96.toShort() })
    }

    @Test
    fun `marker v2 carries canonical stack identity in 32 byte task storage`() {
        val v2 = TierEBpfProgramBuilder.buildMarkerProgram(
            TierEOptions(benchmarkPhases = true, markerVersion = MarkerVersion.V2),
        )
        val v1 = TierEBpfProgramBuilder.buildMarkerProgram(TierEOptions())
        assertTrue(v2.size > v1.size)
        // The marker ABI is RDI=invocation, RSI=stack, RDX=flags.
        val stackRead = v2.indexOfFirst { it.code == 0x79 && it.src == 9 && it.dst == 3 && it.off == 104.toShort() }
        val flagsRead = v2.indexOfFirst { it.code == 0x79 && it.src == 9 && it.dst == 8 && it.off == 96.toShort() }
        assertTrue(stackRead >= 0 && flagsRead > stackRead)
        val storedStack = v2.indexOfFirst { it.code == 0x7b && it.dst == 10 && it.src == 3 && it.off == (-48).toShort() }
        val storedGeneration = v2.indexOfFirst { it.code == 0x63 && it.dst == 10 && it.off == (-44).toShort() }
        assertTrue(storedStack >= 0 && storedGeneration > storedStack, "generation must not be overwritten by stack storage")
        assertTrue(v2.any { it.code == 0x61 && it.src == 10 && it.off == (-48).toShort() }, "stack ID must be zero-extended before storing in task state")
        // The v2 creation helper receives a fully zero-initialized 32-byte
        // stack region starting at FP-32, not the old FP-24 v1 initializer.
        assertTrue(v2.any { it.code == 0x07 && it.dst == 3 && it.imm == -32 })
        // Writes stack_trace_id at task-storage offset 24.
        assertTrue(v2.any { it.code == 0x7b && it.off == 24.toShort() })
        assertEquals(0x95, v2.last().code)
    }

    @Test
    fun `exec program clears 32 bytes for marker v2`() {
        val v1 = TierEBpfProgramBuilder.buildProcessExecProgram(TierEOptions())
        val v2 = TierEBpfProgramBuilder.buildProcessExecProgram(
            TierEOptions(benchmarkPhases = true, markerVersion = MarkerVersion.V2),
        )
        assertTrue(v2.size > v1.size)
        assertTrue(v2.any { it.code == 0x7b && it.off == 24.toShort() })
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
    fun `benchmark coverage variants change only optional diagnostic map work`() {
        fun uniqueProgram(mode: TierEKernelCoverageMode) =
            TierEBpfProgramBuilder.buildSysEnterProgram(
            TierEOptions(
                emissionMode = TierEEmissionMode.UNIQUE_STACK_SYSCALL,
                benchmarkPhases = true,
                kernelCoverageMode = mode,
            ),
        )

        val none = uniqueProgram(TierEKernelCoverageMode.NONE)
        val lookup = uniqueProgram(TierEKernelCoverageMode.DIAGNOSTIC_LOOKUP)
        val update = uniqueProgram(TierEKernelCoverageMode.DIAGNOSTIC_UPDATE)

        fun ringSubmits(program: List<TierEbpfEngine.Insn>) = program.count { it.code == 0x85 && it.imm == 132 }

        fun coverageMapLoads(program: List<TierEbpfEngine.Insn>) = program.count { it.code == 0x18 && it.src == 1 && it.imm == 4 }

        fun lookupCalls(program: List<TierEbpfEngine.Insn>) = program.count { it.code == 0x85 && it.imm == 1 }

        fun updateCalls(program: List<TierEbpfEngine.Insn>) = program.count { it.code == 0x85 && it.imm == 2 }

        assertEquals(0, coverageMapLoads(none))
        assertEquals(1, coverageMapLoads(lookup))
        assertEquals(2, coverageMapLoads(update))
        assertEquals(lookupCalls(none) + 1, lookupCalls(lookup))
        assertEquals(lookupCalls(lookup), lookupCalls(update))
        assertEquals(updateCalls(none), updateCalls(lookup))
        assertEquals(updateCalls(lookup) + 1, updateCalls(update))
        assertEquals(ringSubmits(none), ringSubmits(lookup))
        assertEquals(ringSubmits(lookup), ringSubmits(update))
    }

    @Test
    fun `disabled recovery preserves existing program without recovery accesses`() {
        val disabled = TierEBpfProgramBuilder.buildSysEnterProgram(TierEOptions())
        val default = TierEBpfProgramBuilder.buildSysEnterProgram(
            TierEOptions(benchmarkPhases = true),
        )
        assertEquals(default.size, disabled.size)
    }

    @Test
    fun `recovery producer inserts before ring without suppressing events`() {
        fun sysEnter(mode: CoverageRecoveryMode) =
            TierEBpfProgramBuilder.buildSysEnterProgram(
                TierEOptions(
                    benchmarkPhases = true,
                    markerVersion = MarkerVersion.V2,
                    coverageRecoveryMode = mode,
                ),
            )
        val disabled = sysEnter(CoverageRecoveryMode.DISABLED)
        val shadow = sysEnter(CoverageRecoveryMode.SHADOW)
        val recover = sysEnter(CoverageRecoveryMode.RECOVER)

        fun ringSubmits(program: List<TierEbpfEngine.Insn>) = program.count { it.code == 0x85 && it.imm == 132 }

        fun recoveryLoads(program: List<TierEbpfEngine.Insn>) = program.count { it.code == 0x18 && it.src == 1 && it.imm == 8 }

        fun noExistUpdates(program: List<TierEbpfEngine.Insn>): Int {
            var count = 0
            program.forEachIndexed { index, insn ->
                if (insn.code == 0x85 && insn.imm == 2 && index >= 1) {
                    val flag = program[index - 1]
                    if (flag.code == 0xb7 && flag.dst == 4 && flag.imm == 1) count++
                }
            }
            return count
        }

        assertEquals(0, recoveryLoads(disabled))
        assertEquals(1, recoveryLoads(shadow))
        assertEquals(1, recoveryLoads(recover))
        assertEquals(0, noExistUpdates(disabled))
        assertEquals(1, noExistUpdates(shadow))
        assertEquals(1, noExistUpdates(recover))
        // Ring attempts are unchanged; recovery never suppresses events.
        assertEquals(ringSubmits(disabled), ringSubmits(shadow))
        assertEquals(ringSubmits(shadow), ringSubmits(recover))
        // No map deletion: entries are never evicted during the session.
        assertTrue(shadow.none { it.code == 0x85 && it.imm == 3 })
        assertTrue(recover.none { it.code == 0x85 && it.imm == 3 })
    }

    @Test
    fun `benchmark ring fault branches after retained map insertion`() {
        val options = TierEOptions(
            benchmarkPhases = true,
            markerVersion = MarkerVersion.V2,
            coverageRecoveryMode = CoverageRecoveryMode.RECOVER,
            benchmarkDropRingSyscallNumber = 257,
        )
        val program = TierEBpfProgramBuilder.buildSysEnterProgram(options)
        val insertion = program.indexOfFirst { it.code == 0x18 && it.src == 1 && it.imm == 8 }
        val fault = (insertion + 1 until program.size).first { index ->
            program[index].code == 0x15 && program[index].dst == 5 && program[index].imm == 257
        }
        val reservation = program.indexOfFirst { it.code == 0x85 && it.imm == 131 }
        assertTrue(insertion >= 0 && fault > insertion && reservation > fault)
        assertEquals(1, program.count { it.code == 0x85 && it.imm == 131 }, "normal ring reservation stays present")
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
    fun `minimal sys enter audit preserves target and descriptor lookup while omitting descriptor mutations`() {
        val descriptorAudit = TierEBpfProgramBuilder.buildRawSysEnterAuditProgram()
        val minimalAudit = TierEBpfProgramBuilder.buildMinimalSysEnterAuditProgram()

        assertTrue(minimalAudit.any { it.code == 0x85 && it.imm == 14 }, "minimal audit must retain target identity")
        assertTrue(minimalAudit.any { it.code == 0x85 && it.imm == 8 }, "minimal audit must retain bounded CPU accounting")
        assertTrue(minimalAudit.any { it.code == 0x18 && it.src == 1 && it.imm == 0 }, "minimal audit must retain declared-descriptor lookup")
        assertTrue(minimalAudit.any { it.code == 0x18 && it.src == 1 && it.imm == 1 }, "minimal audit must retain session lookup")
        assertTrue(
            minimalAudit.size < descriptorAudit.size,
            "minimal audit must omit the descriptor-count mutation path",
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
    fun `opt in all target mode emits marker loss reasons without benchmark instrumentation`() {
        val normal = TierEBpfProgramBuilder.buildSysEnterProgram()
        val allTarget = TierEBpfProgramBuilder.buildSysEnterProgram(TierEOptions(allTargetCapture = true))

        assertTrue(normal.none { it.code == 0x61 && it.src == 7 && it.off.toInt() == 16 })
        assertTrue(allTarget.any { it.code == 0x61 && it.src == 7 && it.off.toInt() == 16 }, "capture admission must be independent of marker admission")
        for (reason in listOf(4, 8, 16, 32, 64)) {
            val index = allTarget.indexOfFirst { it.code == 0xb7 && it.dst == 5 && it.imm == reason }
            assertTrue(index >= 0, "missing per-event reason $reason")
            assertTrue(
                reachableInstructions(allTarget, index).any { instruction -> allTarget[instruction].code == 0x85 && allTarget[instruction].imm == 131 },
                "target syscall with reason $reason must reach ring reservation",
            )
        }
    }

    @Test
    fun `raw argument reads cannot silently export invalid slot values`() {
        val program = TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram(TierEOptions(allTargetCapture = true))
        val reads = program.indices.filter { program[it].code == 0x85 && program[it].imm == 113 }

        assertEquals(6, reads.size, "each raw syscall argument needs a checked kernel read")
        assertTrue(reads.all { index -> program[index + 1].code == 0x15 && program[index + 1].dst == 0 && program[index + 1].imm == 0 })
        assertTrue(program.any { it.code == 0x47 && it.imm == 2 }, "failed reads must set FLAG_ARGUMENT_READ_FAILED")
        val submit = program.indexOfFirst { it.code == 0x85 && it.imm == 132 }
        assertTrue(submit > reads.last())
        assertTrue(
            (reads.last() + 1 until submit).any { index ->
                program[index].code == 0x63 && program[index].off.toInt() == 20
            },
            "event flags must be written after argument-read outcomes are known",
        )
    }

    @Test
    fun `all target mode counts gated target handlers and committed ring records separately`() {
        val ordinary = TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram()
        val complete = TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram(TierEOptions(allTargetCapture = true))
        val admitted = complete.indexOfFirst { it.code == 0xb7 && it.dst == 5 && it.imm == 13 }
        val submitted = complete.indexOfFirst { it.code == 0xb7 && it.dst == 5 && it.imm == 14 }
        val captureGate = complete.indexOfFirst { it.code == 0x61 && it.src == 7 && it.off.toInt() == 16 }
        val reserve = complete.indexOfFirst { it.code == 0x85 && it.imm == 131 }
        val publish = complete.indexOfFirst { it.code == 0x85 && it.imm == 132 }

        assertTrue(ordinary.none { it.code == 0xb7 && it.dst == 5 && it.imm in setOf(13, 14) })
        assertTrue(captureGate in 0 until admitted && admitted in 0 until reserve)
        assertTrue(submitted > publish, "submitted counter must follow the irreversible ring submission")
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
            TierEOptions(benchmarkPhases = true, benchmarkExactAdmissionAudit = true),
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
    fun `in flight completion resets key after diagnostic rejection`() {
        val program = TierEBpfProgramBuilder.buildSysEnterProgram(
            TierEOptions(benchmarkPhases = true, benchmarkEmitMarkerLossEvents = true),
        )
        val completionMap = program.indexOfLast { it.code == 0x18 && it.src == 1 && it.imm == 6 }
        assertTrue(completionMap >= 2)
        assertEquals(0xb7, program[completionMap - 2].code)
        assertEquals(0, program[completionMap - 2].imm)
        assertEquals(0x63, program[completionMap - 1].code)
        assertEquals((-4).toShort(), program[completionMap - 1].off)
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
    fun `exact admission audit starts before target filtering and preserves raw argument validation`() {
        val program = TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram(
            TierEOptions(benchmarkPhases = true, benchmarkExactAdmissionAudit = true),
        )
        val taskStorageGet = program.indexOfFirst { it.code == 0x85 && it.imm == 156 }
        val auditMapLoad = program.indexOfFirst { it.code == 0x18 && it.src == 1 && it.imm == 7 }

        assertTrue(auditMapLoad < taskStorageGet, "exact-admission audit must account for target and marker rejection paths")
        assertTrue(
            program.take(auditMapLoad).any { it.code == 0x85 && it.imm == 113 },
            "raw exact-admission audit must read the declared descriptor through the kernel-read helper",
        )
    }

    @Test
    fun `benchmark-only low32 ingress distinguishes main dispatch from an early full-width syscall gate`() {
        val program = TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram(
            TierEOptions(benchmarkPhases = true, benchmarkExactAdmissionAudit = true),
        )
        val low32Id = program.indexOfFirst { it.code == 0x61 && it.src == 9 && it.off == 8.toShort() }
        val earlyIngress = program.indexOfFirst { it.code == 0xdb && it.off == 144.toShort() }
        val fullWidthId = program.indexOfFirst { it.code == 0x79 && it.src == 9 && it.off == 8.toShort() }
        val existingHandler = (fullWidthId + 1 until program.size).firstOrNull { index ->
            program[index].code == 0xdb && program[index].off == 0.toShort()
        } ?: -1
        assertTrue(low32Id >= 0 && earlyIngress > low32Id && fullWidthId > earlyIngress && existingHandler > fullWidthId)
        assertEquals(152, TierEbpfEngine.ExactAdmissionAuditCount.VALUE_SIZE)
        val production = TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram(TierEOptions())
        assertTrue(production.none { it.code == 0xdb && it.off == 144.toShort() })
    }

    @Test
    fun `opt-in main target counter precedes descriptor read and is absent from production`() {
        val audited = TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram(
            TierEOptions(benchmarkPhases = true, benchmarkRawSysEnterAudit = true, benchmarkExactAdmissionAudit = true),
        )
        val targetCounterMap = audited.indexOfFirst { it.code == 0x18 && it.imm == 9 }
        val firstArgumentRead = audited.indexOfFirst { it.code == 0x85 && it.imm == 113 }
        assertTrue(targetCounterMap in 1 until firstArgumentRead)
        assertEquals(2, audited.count { it.code == 0x18 && it.imm == 9 }, "arming and counting must use the same map")
        val production = TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram(TierEOptions())
        assertTrue(production.none { it.code == 0x18 && it.imm == 9 })
    }

    @Test
    fun `independent raw target counter precedes argument read without altering ordinary raw audit`() {
        val audited = TierEBpfProgramBuilder.buildRawTracepointSysEnterAuditProgram(earlyTargetCount = true)
        val targetCounterMap = audited.indexOfFirst { it.code == 0x18 && it.imm == 4 }
        val firstArgumentRead = audited.indexOfFirst { it.code == 0x85 && it.imm == 113 }
        assertTrue(targetCounterMap in 1 until firstArgumentRead)
        assertEquals(2, audited.count { it.code == 0x18 && it.imm == 4 }, "raw attachment must honor the shared arm gate")
        val ordinary = TierEBpfProgramBuilder.buildRawTracepointSysEnterAuditProgram()
        assertTrue(ordinary.none { it.code == 0x18 && it.imm == 4 })
    }

    @Test
    fun `version four raw descriptor checkpoint precedes mutable session gate for installed target`() {
        val program = TierEBpfProgramBuilder.buildRawTracepointSysEnterAuditProgram(
            earlyTargetCount = true,
            benchmarkTargetTgid = 12345,
        )
        val beforeSession = program.indexOfFirst { it.code == 0x18 && it.imm == 5 }
        val firstSession = program.indexOfFirst { it.code == 0x18 && it.imm == 1 }
        val firstArgRead = program.indexOfFirst { it.code == 0x85 && it.imm == 113 }
        assertTrue(firstArgRead in 1 until beforeSession && beforeSession < firstSession)
        assertTrue(program.take(beforeSession).any { it.imm == 12345 }, "immutable installed TGID must gate per-ID observations")
        assertTrue(program.take(beforeSession).any { it.code == 0x35 && it.imm == 131072 }, "ambient out-of-range FDs are not declared map failures")
        assertEquals(2, program.count { it.code == 0x18 && it.imm == 5 }, "raw pre/post session stages must use the same bounded map")
        assertTrue(TierEBpfProgramBuilder.buildRawTracepointSysEnterAuditProgram().none { it.code == 0x18 && it.imm == 5 })
    }

    @Test
    fun `version four main per descriptor checkpoint precedes session and retains no audit disabled bytecode`() {
        val options = TierEOptions(benchmarkPhases = true, benchmarkRawSysEnterAudit = true, benchmarkExactAdmissionAudit = true)
        val program = TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram(options, benchmarkTargetTgid = 12345)
        val beforeSession = program.indexOfFirst { it.code == 0x18 && it.imm == 10 }
        val firstSession = program.indexOfFirst { it.code == 0x18 && it.imm == 3 }
        val firstArgRead = program.indexOfFirst { it.code == 0x85 && it.imm == 113 }
        assertTrue(firstArgRead in 1 until beforeSession && beforeSession < firstSession)
        assertTrue(program.take(beforeSession).any { it.imm == 12345 })
        assertTrue(program.take(beforeSession).any { it.code == 0x35 && it.imm == 131072 })
        assertEquals(2, program.count { it.code == 0x18 && it.imm == 10 })
        assertTrue(TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram(TierEOptions()).none { it.code == 0x18 && it.imm == 10 })
    }

    @Test
    fun `v4 probe cannot carry a nonzero scratch register into exact audit eligibility`() {
        val options = TierEOptions(benchmarkPhases = true, benchmarkRawSysEnterAudit = true, benchmarkExactAdmissionAudit = true)
        val program = TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram(options, benchmarkTargetTgid = 12345)
        val eligibilityInitialization = program.indexOfFirst { it.code == 0x63 && it.dst == 10 && it.src == 5 && it.off == (-100).toShort() }
        assertTrue(eligibilityInitialization > 0)
        assertEquals(
            TierEbpfEngine.Insn(code = 0xb7, dst = 5, imm = 0),
            program[eligibilityInitialization - 1],
            "non-openat entries must not inherit a positive audit eligibility flag from the pre-gate probe",
        )
    }

    @Test
    fun `v4 main post session descriptor stage rejects non openat before reading scratch key`() {
        val options = TierEOptions(benchmarkPhases = true, benchmarkRawSysEnterAudit = true, benchmarkExactAdmissionAudit = true)
        val program = TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram(options, benchmarkTargetTgid = 12345)
        val postStageMap = program.indexOfLast { it.code == 0x18 && it.imm == 10 }
        val precedingSessionMap = (0 until postStageMap).last { program[it].code == 0x18 && program[it].imm == 3 }
        assertTrue(
            program.subList(precedingSessionMap, postStageMap).any { it.code == 0x55 && it.imm == 257 },
            "non-openat syscall must branch around the main post-session descriptor lookup",
        )
    }

    @Test
    fun `exact admission audit records every pre-admission rejection reason without entering the ring path`() {
        val program = TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram(
            TierEOptions(benchmarkPhases = true, benchmarkExactAdmissionAudit = true),
        )

        val auditWrites = program
            .filter { instruction ->
            instruction.code == 0xdb && instruction.off.toInt() in setOf(72, 80, 88, 96, 104, 112, 120, 128, 136)
        }.map { it.off.toInt() }
            .toSet()

        assertEquals(setOf(72, 80, 88, 96, 104, 112, 120, 128, 136), auditWrites)
    }

    @Test
    fun `benchmark admission audit accounts for every boundary through ring submission`() {
        val program = TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram(
            TierEOptions(benchmarkPhases = true, benchmarkExactAdmissionAudit = true),
        )

        val stageOffsets = program
            .filter { instruction -> instruction.code == 0xdb }
            .map { instruction -> instruction.off.toInt() }
            .toSet()

        assertTrue(
            setOf(0, 8, 16, 24, 32, 40, 48, 56, 64).all(stageOffsets::contains),
            "audit must retain handler, session, in-flight, marker, task, invocation, generation, reservation, and submission stages",
        )
    }

    @Test
    fun `audit-disabled program does not reference the benchmark admission map`() {
        val program = TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram(TierEOptions())

        assertTrue(
            program.none { instruction -> instruction.code == 0x18 && instruction.src == 1 && instruction.imm == 7 },
            "production programs must not access the benchmark-only admission map",
        )
    }

    @Test
    fun `audit failure accounting uses isolated scratch storage`() {
        val program = TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram(
            TierEOptions(benchmarkPhases = true, benchmarkExactAdmissionAudit = true),
        )

        assertTrue(
            program.any { instruction -> instruction.code == 0x07 && instruction.dst == 2 && instruction.imm == -120 },
            "benchmark audit failures must not overwrite the session-map key at stack offset -4",
        )
    }

    @Test
    fun `raw tracepoint audit counts failed pt regs reads instead of consuming scratch state`() {
        val program = TierEBpfProgramBuilder.buildRawTracepointSysEnterAuditProgram()
        val probeRead = program.indexOfFirst { instruction -> instruction.code == 0x85 && instruction.imm == 113 }

        assertEquals(0x55, program[probeRead + 1].code, "raw audit must branch on bpf_probe_read_kernel failure")
        assertTrue(
            program.any { instruction -> instruction.code == 0x18 && instruction.src == 1 && instruction.imm == 2 },
            "raw audit must receive the loss-counter map for the read-failure diagnostic",
        )
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
        assertTrue(program.any { it.code == 0x18 && it.src == 1 && it.imm == 3 }, "audit must load the benchmark-only per-cpu counter map")
        assertTrue(program.any { it.code == 0x85 && it.imm == 8 }, "audit must identify the executing CPU")
        assertTrue(
            program.any { it.code == 0x63 && it.dst == 10 && it.off.toInt() == -12 } &&
                program.any { it.code == 0x07 && it.dst == 2 && it.imm == -12 },
            "audit must use a dedicated zero-valued stack key for session lookup after reusing the descriptor key",
        )
        assertTrue(program.any { it.code == 0x61 && it.src == 9 && it.off.toInt() == 8 }, "audit must read the raw syscall id")
        assertTrue(program.any { it.code == 0x15 && it.imm == 257 }, "audit must admit openat")
        assertTrue(program.any { it.code == 0x55 && it.imm == 437 }, "audit must reject every syscall outside the openat family")
        assertTrue(program.any { it.code == 0x79 && it.src == 9 && it.off.toInt() == 16 }, "audit must key by openat directory descriptor")
        assertEquals(
            setOf(0, 8, 16, 24, 32),
            program.filter { it.code == 0xdb && it.dst == 7 }.map { it.off.toInt() }.toSet(),
            "audit must retain pre-session, session-filter, and separate openat-family counters",
        )
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
