package io.mazewall.profiler.tierE.engine

import io.mazewall.profiler.attribution.TierEEmissionMode
import io.mazewall.profiler.attribution.TierEOptions
import io.mazewall.profiler.tierE.engine.TierEbpfEngine.Insn
import io.mazewall.profiler.tierE.ringbuf.SyscallInvocationEvent

/**
 * Pure eBPF bytecode builder for Tier E marker uprobe and sys_enter raw tracepoint programs.
 *
 * Extracted from [TierEbpfEngine] to allow unit testing and isolated verification of bytecode
 * generation without requiring root privileges or bpf(2) syscall execution.
 */
public object TierEBpfProgramBuilder {
    // eBPF helper IDs (uapi/linux/bpf.h)
    private const val H_LOOKUP: Int = 1
    private const val H_UPDATE: Int = 2
    private const val H_KTIME: Int = 5
    private const val H_PID_TGID: Int = 14
    private const val H_PROBE_READ_KERNEL: Int = 113
    private const val H_RB_RESERVE: Int = 131
    private const val H_RB_SUBMIT: Int = 132
    private const val H_TASK_STORAGE_GET: Int = 156
    private const val H_CURRENT_TASK_BTF: Int = 158

    // eBPF opcodes (64-bit class)
    private const val ADD_IMM: Int = 0x07
    private const val LDX_W: Int = 0x61
    private const val LDX_DW: Int = 0x79
    private const val STX_W: Int = 0x63
    private const val STX_DW: Int = 0x7b
    private const val JEQ_IMM: Int = 0x15
    private const val JA: Int = 0x05
    private const val JNE_REG: Int = 0x5d
    private const val OR_IMM: Int = 0x47
    private const val RSH_IMM: Int = 0x77
    private const val LD_MAP_FD: Int = 0x18
    private const val MOV_IMM: Int = 0xb7
    private const val MOV_REG: Int = 0xbf
    private const val CALL: Int = 0x85
    private const val EXIT: Int = 0x95

    /** Atomic add-and-store opcode: lock *(u64*)(dst+off) += src. */
    private const val ATOMIC_ADD_DW: Int = 0xdb

    private const val PSEUDO_FD: Int = 1
    private const val FP: Int = 10

    /**
     * Builds the raw_tracepoint/sys_enter eBPF program for capturing syscall events with active invocation state.
     */
    public fun buildSysEnterProgram(options: TierEOptions = TierEOptions()): List<Insn> {
        val p = mutableListOf<Insn>()

        fun emit(i: Insn): Int {
            p += i
            return p.size - 1
        }

        fun ldMap(
            dst: Int,
            idx: Int,
        ) {
            emit(Insn(LD_MAP_FD, dst = dst, src = PSEUDO_FD, imm = idx))
            emit(Insn(code = 0x00))
        }

        val exits = mutableListOf<Int>()

        fun exitIfZero(reg: Int) {
            exits.add(emit(Insn(JEQ_IMM, dst = reg)))
        }

        emit(Insn(MOV_REG, dst = 9, src = 1))
        emit(Insn(CALL, imm = H_PID_TGID))
        emit(Insn(MOV_REG, dst = 8, src = 0))
        emit(Insn(MOV_IMM, dst = 5, imm = 0))
        emit(Insn(STX_W, dst = FP, src = 5, off = -4))

        // Reject every process except the configured target before consulting
        // task storage. This is required because both the raw tracepoint and
        // dynamic uprobe attachment are system-wide.
        ldMap(1, 3)
        emit(Insn(MOV_REG, dst = 2, src = FP))
        emit(Insn(ADD_IMM, dst = 2, imm = -4))
        emit(Insn(CALL, imm = H_LOOKUP))
        exitIfZero(0)
        emit(Insn(LDX_W, dst = 4, src = 0, off = 4))
        emit(Insn(MOV_REG, dst = 5, src = 8))
        emit(Insn(RSH_IMM, dst = 5, imm = 32))
        exits.add(emit(Insn(JNE_REG, dst = 5, src = 4)))

        emit(Insn(LDX_W, dst = 5, src = 0, off = 8))
        val markerDetached = emit(Insn(JEQ_IMM, dst = 5))

        // A detached marker must bypass task storage completely. Otherwise an
        // old task-storage value could be copied into a new ring observation.
        val detachedObservation = p.size
        emit(Insn(MOV_IMM, dst = 5, imm = 0))
        emit(Insn(STX_DW, dst = FP, src = 5, off = -24))
        emit(Insn(STX_DW, dst = FP, src = 5, off = -16))
        emit(Insn(STX_W, dst = FP, src = 5, off = -8))
        val detachedToReserve = emit(Insn(JA))

        // Default to an explicit un-attributed observation. A missing marker is
        // evidence, not permission to hide the target syscall.
        val defaultObservation = p.size
        emit(Insn(MOV_IMM, dst = 5, imm = 0))
        emit(Insn(STX_DW, dst = FP, src = 5, off = -24))
        emit(Insn(STX_DW, dst = FP, src = 5, off = -16))
        emit(Insn(STX_W, dst = FP, src = 5, off = -8))
        ldMap(6, 0)
        emit(Insn(CALL, imm = H_CURRENT_TASK_BTF))
        val noTask = emit(Insn(JEQ_IMM, dst = 0))
        emit(Insn(MOV_REG, dst = 1, src = 6))
        emit(Insn(MOV_REG, dst = 2, src = 0))
        emit(Insn(MOV_IMM, dst = 3, imm = 0))
        emit(Insn(MOV_IMM, dst = 4, imm = 0))
        emit(Insn(CALL, imm = H_TASK_STORAGE_GET))
        val noStorage = emit(Insn(JEQ_IMM, dst = 0))
        emit(Insn(MOV_REG, dst = 6, src = 0))
        emit(Insn(LDX_DW, dst = 5, src = 6, off = 0))
        emit(Insn(STX_DW, dst = FP, src = 5, off = -24))
        emit(Insn(LDX_DW, dst = 5, src = 6, off = 8))
        emit(Insn(STX_DW, dst = FP, src = 5, off = -16))
        emit(Insn(LDX_W, dst = 5, src = 6, off = 16))
        emit(Insn(STX_W, dst = FP, src = 5, off = -8))

        // Diagnostic counts are keyed by the active invocation, bounded by
        // the map's LRU capacity, and never participate in attribution.
        emit(Insn(LDX_DW, dst = 5, src = FP, off = -24))
        val noMetricContext = emit(Insn(JEQ_IMM, dst = 5))
        emit(Insn(STX_DW, dst = FP, src = 5, off = -72))
        ldMap(1, 5)
        emit(Insn(MOV_REG, dst = 2, src = FP))
        emit(Insn(ADD_IMM, dst = 2, imm = -72))
        emit(Insn(CALL, imm = H_LOOKUP))
        val newMetricContext = emit(Insn(JEQ_IMM, dst = 0))
        emit(Insn(MOV_IMM, dst = 5, imm = 1))
        emit(Insn(ATOMIC_ADD_DW, dst = 0, src = 5))
        val metricCounted = emit(Insn(JA))
        val metricInsert = p.size
        ldMap(1, 5)
        emit(Insn(MOV_REG, dst = 2, src = FP))
        emit(Insn(ADD_IMM, dst = 2, imm = -72))
        emit(Insn(MOV_IMM, dst = 5, imm = 1))
        emit(Insn(STX_DW, dst = FP, src = 5, off = -80))
        emit(Insn(MOV_REG, dst = 3, src = FP))
        emit(Insn(ADD_IMM, dst = 3, imm = -80))
        emit(Insn(MOV_IMM, dst = 4, imm = 0))
        emit(Insn(CALL, imm = H_UPDATE))
        val metricDone = p.size

        // Unique-edge mode keeps a bounded kernel observation map, but never
        // suppresses ring-buffer observations. The collector owns suppression
        // because only it can commit an edge after downstream acknowledgement.
        var contextHasNoCoverage: Int? = null
        if (options.emissionMode == TierEEmissionMode.UNIQUE_STACK_SYSCALL) {
            // Context 0 is the explicit "no marker" state. It is evidence of
            // incomplete attribution and must remain visible on every syscall;
            // it is never a coverage edge.
            emit(Insn(LDX_DW, dst = 5, src = FP, off = -24))
            contextHasNoCoverage = emit(Insn(JEQ_IMM, dst = 5))
            emit(Insn(LDX_DW, dst = 5, src = 9, off = 8))
            emit(Insn(STX_W, dst = FP, src = 5, off = -40))
            emit(Insn(LDX_DW, dst = 5, src = FP, off = -24))
            emit(Insn(STX_DW, dst = FP, src = 5, off = -56))
            emit(Insn(MOV_IMM, dst = 5, imm = 0))
            emit(Insn(STX_DW, dst = FP, src = 5, off = -48))
            emit(Insn(LDX_W, dst = 5, src = FP, off = -40))
            emit(Insn(STX_W, dst = FP, src = 5, off = -48))
        }

        val reserveStart = p.size
        p[noMetricContext] = p[noMetricContext].copy(off = (reserveStart - noMetricContext - 1).toShort())
        p[newMetricContext] = p[newMetricContext].copy(off = (metricInsert - newMetricContext - 1).toShort())
        p[metricCounted] = p[metricCounted].copy(off = (metricDone - metricCounted - 1).toShort())
        contextHasNoCoverage?.let { p[it] = p[it].copy(off = (reserveStart - it - 1).toShort()) }
        ldMap(1, 1)
        emit(Insn(MOV_IMM, dst = 2, imm = 88))
        emit(Insn(MOV_IMM, dst = 3, imm = 0))
        emit(Insn(CALL, imm = H_RB_RESERVE))
        val reservationFailed = emit(Insn(JEQ_IMM, dst = 0))
        emit(Insn(MOV_REG, dst = 7, src = 0))
        emit(Insn(CALL, imm = H_KTIME))
        emit(Insn(STX_DW, dst = 7, src = 0, off = 0))
        emit(Insn(MOV_REG, dst = 5, src = 8))
        emit(Insn(RSH_IMM, dst = 5, imm = 32))
        emit(Insn(STX_W, dst = 7, src = 5, off = 8))
        emit(Insn(STX_W, dst = 7, src = 8, off = 12))
        emit(Insn(LDX_DW, dst = 5, src = 9, off = 8))
        emit(Insn(STX_W, dst = 7, src = 5, off = 16))
        emit(Insn(LDX_W, dst = 5, src = FP, off = -8))
        emit(Insn(STX_W, dst = 7, src = 5, off = 20))
        emit(Insn(LDX_DW, dst = 5, src = FP, off = -24))
        emit(Insn(STX_DW, dst = 7, src = 5, off = 24))
        emit(Insn(LDX_DW, dst = 5, src = FP, off = -16))
        emit(Insn(STX_DW, dst = 7, src = 5, off = 32))
        // raw_tracepoint_args only exposes the pt_regs address as an untyped
        // scalar. The verifier therefore requires probe_read_kernel rather
        // than direct LDX instructions through that address.
        emit(Insn(LDX_DW, dst = 6, src = 9, off = 0))
        listOf(112, 104, 96, 56, 72, 64).forEachIndexed { index, offset ->
            emit(Insn(MOV_REG, dst = 1, src = 7))
            emit(Insn(ADD_IMM, dst = 1, imm = 40 + index * 8))
            emit(Insn(MOV_IMM, dst = 2, imm = 8))
            emit(Insn(MOV_REG, dst = 3, src = 6))
            emit(Insn(ADD_IMM, dst = 3, imm = offset))
            emit(Insn(CALL, imm = H_PROBE_READ_KERNEL))
            emit(Insn(JEQ_IMM, dst = 0, off = 3))
            emit(Insn(LDX_W, dst = 5, src = 7, off = 20))
            emit(Insn(OR_IMM, dst = 5, imm = SyscallInvocationEvent.FLAG_ARGUMENT_READ_FAILED))
            emit(Insn(STX_W, dst = 7, src = 5, off = 20))
        }
        if (options.emissionMode == TierEEmissionMode.UNIQUE_STACK_SYSCALL) {
            // Do not turn a malformed observation into a covered edge. A later
            // syscall from the same stack must be allowed to provide the first
            // valid representative instead of being suppressed by this one.
            emit(Insn(LDX_DW, dst = 5, src = FP, off = -24))
            val noContextForUpdate = emit(Insn(JEQ_IMM, dst = 5))
            emit(Insn(LDX_W, dst = 5, src = 7, off = 20))
            val argumentsWereRead = emit(Insn(JEQ_IMM, dst = 5))
            val skipInvalidCoverageUpdate = emit(Insn(JA))
            val coverageUpdateStart = p.size
            ldMap(1, 4)
            emit(Insn(MOV_REG, dst = 2, src = FP))
            emit(Insn(ADD_IMM, dst = 2, imm = -56))
            emit(Insn(MOV_IMM, dst = 5, imm = 1))
            emit(Insn(STX_DW, dst = FP, src = 5, off = -64))
            emit(Insn(MOV_REG, dst = 3, src = FP))
            emit(Insn(ADD_IMM, dst = 3, imm = -64))
            emit(Insn(MOV_IMM, dst = 4, imm = 0))
            emit(Insn(CALL, imm = H_UPDATE))
            val afterCoverageUpdate = p.size
            p[argumentsWereRead] = p[argumentsWereRead].copy(off = (coverageUpdateStart - argumentsWereRead - 1).toShort())
            p[noContextForUpdate] = p[noContextForUpdate].copy(off = (afterCoverageUpdate - noContextForUpdate - 1).toShort())
            p[skipInvalidCoverageUpdate] = p[skipInvalidCoverageUpdate].copy(off = (afterCoverageUpdate - skipInvalidCoverageUpdate - 1).toShort())
        }
        emit(Insn(MOV_REG, dst = 1, src = 7))
        emit(Insn(MOV_IMM, dst = 2, imm = 0))
        emit(Insn(CALL, imm = H_RB_SUBMIT))
        val submitted = emit(Insn(JA))
        val lossHandler = p.size
        ldMap(1, 2)
        emit(Insn(MOV_REG, dst = 2, src = FP))
        emit(Insn(ADD_IMM, dst = 2, imm = -4))
        emit(Insn(CALL, imm = H_LOOKUP))
        val missingLossCounter = emit(Insn(JEQ_IMM, dst = 0))
        emit(Insn(MOV_IMM, dst = 5, imm = 1))
        emit(Insn(ATOMIC_ADD_DW, dst = 0, src = 5))
        emit(Insn(MOV_IMM, dst = 0, imm = 0))
        emit(Insn(EXIT))
        val exitIndex = p.lastIndex
        p[reservationFailed] = p[reservationFailed].copy(off = (lossHandler - reservationFailed - 1).toShort())
        p[noTask] = p[noTask].copy(off = (reserveStart - noTask - 1).toShort())
        p[noStorage] = p[noStorage].copy(off = (reserveStart - noStorage - 1).toShort())
        p[markerDetached] = p[markerDetached].copy(off = (detachedObservation - markerDetached - 1).toShort())
        p[detachedToReserve] = p[detachedToReserve].copy(off = (reserveStart - detachedToReserve - 1).toShort())
        p[submitted] = p[submitted].copy(off = (exitIndex - submitted - 2).toShort())
        p[missingLossCounter] = p[missingLossCounter].copy(off = (exitIndex - missingLossCounter - 1).toShort())
        exits.forEach { index -> p[index] = p[index].copy(off = (exitIndex - index - 1).toShort()) }

        return p.toList()
    }

    /**
     * Builds the x86_64 uprobe program for `mazewall_stack_marker(uint64_t)`.
     */
    public fun buildMarkerProgram(): List<Insn> {
        val program = mutableListOf<Insn>()

        fun emit(instruction: Insn): Int {
            program += instruction
            return program.lastIndex
        }

        fun loadMap(
            destination: Int,
            index: Int,
        ) {
            emit(Insn(LD_MAP_FD, dst = destination, src = PSEUDO_FD, imm = index))
            emit(Insn(code = 0))
        }

        emit(Insn(MOV_REG, dst = 9, src = 1))
        emit(Insn(CALL, imm = H_PID_TGID))
        emit(Insn(MOV_REG, dst = 6, src = 0))
        emit(Insn(MOV_IMM, dst = 5, imm = 0))
        emit(Insn(STX_W, dst = FP, src = 5, off = -28))
        loadMap(1, 2)
        emit(Insn(MOV_REG, dst = 2, src = FP))
        emit(Insn(ADD_IMM, dst = 2, imm = -28))
        emit(Insn(CALL, imm = H_LOOKUP))
        val noTargetConfig = emit(Insn(JEQ_IMM, dst = 0))
        emit(Insn(LDX_W, dst = 4, src = 0, off = 4))
        emit(Insn(MOV_REG, dst = 5, src = 6))
        emit(Insn(RSH_IMM, dst = 5, imm = 32))
        val wrongTarget = emit(Insn(JNE_REG, dst = 5, src = 4))
        emit(Insn(LDX_DW, dst = 7, src = 9, off = 112)) // pt_regs.di
        emit(Insn(LDX_DW, dst = 8, src = 9, off = 104)) // pt_regs.si
        val clearMarker = emit(Insn(JEQ_IMM, dst = 7))
        emit(Insn(MOV_REG, dst = 5, src = 7))
        emit(Insn(RSH_IMM, dst = 5, imm = 32))
        emit(Insn(STX_W, dst = FP, src = 5, off = -32))
        emit(Insn(MOV_IMM, dst = 4, imm = 0))
        emit(Insn(STX_W, dst = FP, src = 4, off = -28))
        loadMap(1, 2)
        emit(Insn(MOV_REG, dst = 2, src = FP))
        emit(Insn(ADD_IMM, dst = 2, imm = -28))
        emit(Insn(CALL, imm = H_LOOKUP))
        val noSession = emit(Insn(JEQ_IMM, dst = 0))
        emit(Insn(LDX_W, dst = 4, src = 0))
        emit(Insn(LDX_W, dst = 5, src = FP, off = -32))
        val wrongSession = emit(Insn(JNE_REG, dst = 5, src = 4))
        val acceptedMarker = program.size
        emit(Insn(MOV_IMM, dst = 5, imm = 1))
        emit(Insn(STX_W, dst = FP, src = 5, off = -28))
        loadMap(1, 1)
        emit(Insn(MOV_REG, dst = 2, src = FP))
        emit(Insn(ADD_IMM, dst = 2, imm = -28))
        emit(Insn(CALL, imm = H_LOOKUP))
        val noMarkerCounter = emit(Insn(JEQ_IMM, dst = 0))
        emit(Insn(MOV_IMM, dst = 5, imm = 1))
        emit(Insn(ATOMIC_ADD_DW, dst = 0, src = 5))
        emit(Insn(CALL, imm = H_PID_TGID))
        emit(Insn(STX_DW, dst = FP, src = 0, off = -40))
        emit(Insn(MOV_IMM, dst = 5, imm = 2))
        emit(Insn(STX_W, dst = FP, src = 5, off = -28))
        loadMap(1, 1)
        emit(Insn(MOV_REG, dst = 2, src = FP))
        emit(Insn(ADD_IMM, dst = 2, imm = -28))
        emit(Insn(CALL, imm = H_LOOKUP))
        val noPidSlot = emit(Insn(JEQ_IMM, dst = 0))
        emit(Insn(LDX_DW, dst = 5, src = FP, off = -40))
        emit(Insn(STX_DW, dst = 0, src = 5))
        emit(Insn(STX_DW, dst = FP, src = 7, off = -24))
        emit(Insn(MOV_IMM, dst = 5, imm = 0))
        emit(Insn(STX_DW, dst = FP, src = 5, off = -16))
        emit(Insn(STX_DW, dst = FP, src = 5, off = -8))
        loadMap(6, 0)
        emit(Insn(CALL, imm = H_CURRENT_TASK_BTF))
        val noTask = emit(Insn(JEQ_IMM, dst = 0))
        emit(Insn(MOV_REG, dst = 1, src = 6))
        emit(Insn(MOV_REG, dst = 2, src = 0))
        emit(Insn(MOV_REG, dst = 3, src = FP))
        emit(Insn(ADD_IMM, dst = 3, imm = -24))
        emit(Insn(MOV_IMM, dst = 4, imm = 1))
        emit(Insn(CALL, imm = H_TASK_STORAGE_GET))
        val noStorage = emit(Insn(JEQ_IMM, dst = 0))
        emit(Insn(STX_DW, dst = 0, src = 7, off = 0))
        emit(Insn(MOV_IMM, dst = 5, imm = 1))
        emit(Insn(ATOMIC_ADD_DW, dst = 0, src = 5, off = 8))
        emit(Insn(STX_W, dst = 0, src = 8, off = 16))
        val markerStored = emit(Insn(JA))
        val markerFailureHandler = program.size
        emit(Insn(MOV_IMM, dst = 5, imm = 3))
        emit(Insn(STX_W, dst = FP, src = 5, off = -28))
        loadMap(1, 1)
        emit(Insn(MOV_REG, dst = 2, src = FP))
        emit(Insn(ADD_IMM, dst = 2, imm = -28))
        emit(Insn(CALL, imm = H_LOOKUP))
        val noFailureCounter = emit(Insn(JEQ_IMM, dst = 0))
        emit(Insn(MOV_IMM, dst = 5, imm = 1))
        emit(Insn(ATOMIC_ADD_DW, dst = 0, src = 5))
        emit(Insn(MOV_IMM, dst = 0, imm = 0))
        emit(Insn(EXIT))
        val exitIndex = program.lastIndex
        program[clearMarker] = program[clearMarker].copy(off = (acceptedMarker - clearMarker - 1).toShort())
        program[noSession] = program[noSession].copy(off = (exitIndex - noSession - 1).toShort())
        program[noTargetConfig] = program[noTargetConfig].copy(off = (exitIndex - noTargetConfig - 1).toShort())
        program[wrongTarget] = program[wrongTarget].copy(off = (exitIndex - wrongTarget - 1).toShort())
        program[wrongSession] = program[wrongSession].copy(off = (exitIndex - wrongSession - 1).toShort())
        program[noMarkerCounter] = program[noMarkerCounter].copy(off = (exitIndex - noMarkerCounter - 1).toShort())
        program[noPidSlot] = program[noPidSlot].copy(off = (exitIndex - noPidSlot - 1).toShort())
        program[noTask] = program[noTask].copy(off = (markerFailureHandler - noTask - 1).toShort())
        program[noStorage] = program[noStorage].copy(off = (markerFailureHandler - noStorage - 1).toShort())
        program[markerStored] = program[markerStored].copy(off = (exitIndex - markerStored - 2).toShort())
        program[noFailureCounter] = program[noFailureCounter].copy(off = (exitIndex - noFailureCounter - 1).toShort())
        return program
    }
}
