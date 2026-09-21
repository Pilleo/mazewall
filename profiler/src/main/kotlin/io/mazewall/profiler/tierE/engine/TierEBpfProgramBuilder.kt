package io.mazewall.profiler.tierE.engine

import io.mazewall.profiler.internal.attribution.TierEEmissionMode
import io.mazewall.profiler.internal.attribution.TierEKernelCoverageMode
import io.mazewall.profiler.internal.attribution.TierEOptions
import io.mazewall.profiler.internal.attribution.TierERingSubmissionMode
import io.mazewall.profiler.tierE.engine.TierEbpfEngine.Insn
import io.mazewall.profiler.tierE.ringbuf.SyscallInvocationEvent

/**
 * Pure eBPF bytecode builder for Tier E marker uprobe and sys_enter tracepoint programs.
 *
 * Extracted from [TierEbpfEngine] to allow unit testing and isolated verification of bytecode
 * generation without requiring root privileges or bpf(2) syscall execution.
 */
internal object TierEBpfProgramBuilder {
    // eBPF helper IDs (uapi/linux/bpf.h)
    private const val H_LOOKUP: Int = 1
    private const val H_UPDATE: Int = 2
    private const val H_KTIME: Int = 5
    private const val H_SMP_PROCESSOR_ID: Int = 8
    private const val H_PID_TGID: Int = 14
    private const val H_PROBE_READ_KERNEL: Int = 113
    private const val H_RB_RESERVE: Int = 131
    private const val H_RB_SUBMIT: Int = 132
    private const val H_TASK_STORAGE_GET: Int = 156
    private const val H_CURRENT_TASK_BTF: Int = 158

    private const val COUNTER_MAP_INDEX: Int = 2
    private const val MARKER_FAILURE_COUNTER_INDEX: Int = 3
    private const val AMBIENT_INACTIVE_COUNTER_INDEX: Int = 4
    private const val EXACT_ADMISSION_COUNTER_INDEX: Int = 5
    private const val MARKER_DETACHED_COUNTER_INDEX: Int = 6
    private const val TASK_STORAGE_UNAVAILABLE_COUNTER_INDEX: Int = 7
    private const val STALE_MARKER_GENERATION_COUNTER_INDEX: Int = 8
    private const val RAW_AUDIT_ARGUMENT_READ_FAILURE_COUNTER_INDEX: Int = 9
    private const val EXACT_ADMISSION_AUDIT_ARGUMENT_READ_FAILURE_COUNTER_INDEX: Int =
        TierEbpfEngine.EXACT_ADMISSION_AUDIT_ARGUMENT_READ_FAILURE_COUNTER_INDEX
    private const val EXACT_ADMISSION_AUDIT_LOOKUP_FAILURE_COUNTER_INDEX: Int =
        TierEbpfEngine.EXACT_ADMISSION_AUDIT_LOOKUP_FAILURE_COUNTER_INDEX
    private const val RAW_AUDIT_OPENAT_OFFSET: Int = TierEbpfEngine.RawSysEnterAuditCount.OPENAT_OFFSET
    private const val RAW_AUDIT_OPENAT2_OFFSET: Int = TierEbpfEngine.RawSysEnterAuditCount.OPENAT2_OFFSET
    private const val RAW_AUDIT_PRE_SESSION_OFFSET: Int = TierEbpfEngine.RawSysEnterAuditCount.PRE_SESSION_OFFSET
    private const val RAW_AUDIT_NO_SESSION_OFFSET: Int = TierEbpfEngine.RawSysEnterAuditCount.NO_SESSION_OFFSET
    private const val RAW_AUDIT_WRONG_TARGET_OFFSET: Int = TierEbpfEngine.RawSysEnterAuditCount.WRONG_TARGET_OFFSET
    private const val IN_FLIGHT_MAP_INDEX: Int = 6
    private const val EXACT_ADMISSION_AUDIT_MAP_INDEX: Int = 7
    private const val EXACT_ADMISSION_AUDIT_HANDLER_ENTERED_OFFSET: Int = TierEbpfEngine.ExactAdmissionAuditCount.HANDLER_ENTERED_OFFSET
    private const val EXACT_ADMISSION_AUDIT_SESSION_MATCHED_OFFSET: Int = TierEbpfEngine.ExactAdmissionAuditCount.SESSION_MATCHED_OFFSET
    private const val EXACT_ADMISSION_AUDIT_IN_FLIGHT_ADMITTED_OFFSET: Int = TierEbpfEngine.ExactAdmissionAuditCount.IN_FLIGHT_ADMITTED_OFFSET
    private const val EXACT_ADMISSION_AUDIT_MARKER_ATTACHED_OFFSET: Int = TierEbpfEngine.ExactAdmissionAuditCount.MARKER_ATTACHED_OFFSET
    private const val EXACT_ADMISSION_AUDIT_TASK_STORAGE_AVAILABLE_OFFSET: Int = TierEbpfEngine.ExactAdmissionAuditCount.TASK_STORAGE_AVAILABLE_OFFSET
    private const val EXACT_ADMISSION_AUDIT_ACTIVE_INVOCATION_OFFSET: Int = TierEbpfEngine.ExactAdmissionAuditCount.ACTIVE_INVOCATION_OFFSET
    private const val EXACT_ADMISSION_AUDIT_GENERATION_VALIDATED_OFFSET: Int = TierEbpfEngine.ExactAdmissionAuditCount.GENERATION_VALIDATED_OFFSET
    private const val EXACT_ADMISSION_AUDIT_RING_RESERVED_OFFSET: Int = TierEbpfEngine.ExactAdmissionAuditCount.RING_RESERVED_OFFSET
    private const val EXACT_ADMISSION_AUDIT_SUBMITTED_OFFSET: Int = TierEbpfEngine.ExactAdmissionAuditCount.SUBMITTED_OFFSET
    private const val EXACT_ADMISSION_AUDIT_SESSION_UNAVAILABLE_OFFSET: Int = TierEbpfEngine.ExactAdmissionAuditCount.SESSION_UNAVAILABLE_OFFSET
    private const val EXACT_ADMISSION_AUDIT_WRONG_TARGET_OFFSET: Int = TierEbpfEngine.ExactAdmissionAuditCount.WRONG_TARGET_OFFSET
    private const val EXACT_ADMISSION_AUDIT_IN_FLIGHT_UNAVAILABLE_OFFSET: Int = TierEbpfEngine.ExactAdmissionAuditCount.IN_FLIGHT_UNAVAILABLE_OFFSET
    private const val EXACT_ADMISSION_AUDIT_MARKER_DETACHED_OFFSET: Int = TierEbpfEngine.ExactAdmissionAuditCount.MARKER_DETACHED_OFFSET
    private const val EXACT_ADMISSION_AUDIT_MARKER_STATE_UNAVAILABLE_OFFSET: Int = TierEbpfEngine.ExactAdmissionAuditCount.MARKER_STATE_UNAVAILABLE_OFFSET
    private const val EXACT_ADMISSION_AUDIT_TASK_STORAGE_UNAVAILABLE_OFFSET: Int = TierEbpfEngine.ExactAdmissionAuditCount.TASK_STORAGE_UNAVAILABLE_OFFSET
    private const val EXACT_ADMISSION_AUDIT_INACTIVE_INVOCATION_OFFSET: Int = TierEbpfEngine.ExactAdmissionAuditCount.INACTIVE_INVOCATION_OFFSET
    private const val EXACT_ADMISSION_AUDIT_STALE_GENERATION_OFFSET: Int = TierEbpfEngine.ExactAdmissionAuditCount.STALE_MARKER_GENERATION_OFFSET
    private const val EXACT_ADMISSION_AUDIT_RING_RESERVATION_FAILED_OFFSET: Int = TierEbpfEngine.ExactAdmissionAuditCount.RING_RESERVATION_FAILED_OFFSET

    // eBPF opcodes (64-bit class)
    private const val ADD_IMM: Int = 0x07
    private const val LDX_W: Int = 0x61
    private const val LDX_DW: Int = 0x79
    private const val STX_W: Int = 0x63
    private const val STX_DW: Int = 0x7b
    private const val JEQ_IMM: Int = 0x15
    private const val JA: Int = 0x05
    private const val JNE_IMM: Int = 0x55
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

    // struct pt_regs offsets on the supported x86_64 raw-tracepoint ABI.
    private const val X86_64_PT_REGS_DI_OFFSET: Int = 112
    private val X86_64_SYSCALL_ARGUMENT_OFFSETS: IntArray = intArrayOf(112, 104, 96, 56, 72, 64)

    /**
     * Builds a benchmark-only raw-syscalls audit program. Map 0 contains declared directory
     * descriptors as keys with separate observed `openat` and `openat2` counts as values; map 1 is the normal
     * session configuration. The program never reserves or submits a ring record.
     */
    public fun buildRawSysEnterAuditProgram(): List<Insn> = buildSysEnterAuditProgram(rawTracepoint = false, descriptorCounts = true)

    /** x86_64 raw-tracepoint audit with `pt_regs *` argument extraction. */
    public fun buildRawTracepointSysEnterAuditProgram(): List<Insn> = buildSysEnterAuditProgram(rawTracepoint = true, descriptorCounts = true)

    /**
     * Minimal benchmark-only denominator: validates a declared directory descriptor and
     * target TGID, then increments only the bounded CPU counter. It intentionally performs
     * no per-descriptor map mutation.
     */
    public fun buildMinimalSysEnterAuditProgram(): List<Insn> = buildSysEnterAuditProgram(rawTracepoint = false, descriptorCounts = false)

    /** x86_64 raw-tracepoint form of the minimal benchmark-only audit. */
    public fun buildMinimalRawTracepointSysEnterAuditProgram(): List<Insn> = buildSysEnterAuditProgram(rawTracepoint = true, descriptorCounts = false)

    private fun buildSysEnterAuditProgram(
        rawTracepoint: Boolean,
        descriptorCounts: Boolean,
    ): List<Insn> {
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
            emit(Insn(code = 0x00))
        }

        emit(Insn(MOV_REG, dst = 9, src = 1))
        emit(Insn(CALL, imm = H_PID_TGID))
        emit(Insn(MOV_REG, dst = 8, src = 0))
        emit(Insn(MOV_IMM, dst = 5, imm = 0))
        emit(Insn(STX_W, dst = FP, src = 5, off = -4))

        // raw_syscalls/sys_enter: syscall id is u64@8 and args[0] is u64@16.
        emit(Insn(LDX_W, dst = 5, src = 9, off = 8))
        val openat = emit(Insn(JEQ_IMM, dst = 5, imm = 257))
        val nonOpenatFamily = emit(Insn(JNE_IMM, dst = 5, imm = 437))
        emit(Insn(MOV_IMM, dst = 6, imm = 8))
        val openatFamily = emit(Insn(JA))
        val openatCounter = program.size
        emit(Insn(MOV_IMM, dst = 6, imm = 0))
        val descriptorLookup = program.size
        program[openat] = program[openat].copy(off = (openatCounter - openat - 1).toShort())
        program[openatFamily] = program[openatFamily].copy(off = (descriptorLookup - openatFamily - 1).toShort())
        val rawArgumentReadFailure: Int?
        if (rawTracepoint) {
            emit(Insn(LDX_DW, dst = 3, src = 9, off = 0)) // raw args[0] = pt_regs *
            emit(Insn(ADD_IMM, dst = 3, imm = X86_64_PT_REGS_DI_OFFSET))
            emit(Insn(MOV_REG, dst = 1, src = FP))
            emit(Insn(ADD_IMM, dst = 1, imm = -8))
            emit(Insn(MOV_IMM, dst = 2, imm = 8))
            emit(Insn(CALL, imm = H_PROBE_READ_KERNEL))
            rawArgumentReadFailure = emit(Insn(JNE_IMM, dst = 0))
            emit(Insn(LDX_DW, dst = 5, src = FP, off = -8))
        } else {
            rawArgumentReadFailure = null
            emit(Insn(LDX_DW, dst = 5, src = 9, off = 16))
        }
        emit(Insn(STX_W, dst = FP, src = 5, off = -4))
        loadMap(1, 0)
        emit(Insn(MOV_REG, dst = 2, src = FP))
        emit(Insn(ADD_IMM, dst = 2, imm = -4))
        emit(Insn(CALL, imm = H_LOOKUP))
        val undeclaredDescriptor = emit(Insn(JEQ_IMM, dst = 0))
        emit(Insn(MOV_REG, dst = 7, src = 0))
        emit(Insn(MOV_IMM, dst = 5, imm = 1))
        emit(Insn(ATOMIC_ADD_DW, dst = 7, src = 5, off = RAW_AUDIT_PRE_SESSION_OFFSET.toShort()))

        // Audit the session gate only after finding a declared descriptor. The benchmark
        // can now distinguish a syscall the raw tracepoint never matched from one rejected
        // by the global attachment's session filter.
        loadMap(1, 1)
        emit(Insn(MOV_IMM, dst = 5, imm = 0))
        emit(Insn(STX_W, dst = FP, src = 5, off = -12))
        emit(Insn(MOV_REG, dst = 2, src = FP))
        emit(Insn(ADD_IMM, dst = 2, imm = -12))
        emit(Insn(CALL, imm = H_LOOKUP))
        val noSession = emit(Insn(JEQ_IMM, dst = 0))
        emit(Insn(LDX_W, dst = 4, src = 0, off = 4))
        emit(Insn(MOV_REG, dst = 5, src = 8))
        emit(Insn(RSH_IMM, dst = 5, imm = 32))
        val wrongTarget = emit(Insn(JNE_REG, dst = 5, src = 4))

        // The bounded per-CPU array is diagnostic-only and counts only target
        // workload syscalls that have passed the session gate.
        emit(Insn(STX_DW, dst = FP, src = 7, off = -24))
        emit(Insn(CALL, imm = H_SMP_PROCESSOR_ID))
        emit(Insn(STX_W, dst = FP, src = 0, off = -16))
        loadMap(1, 3)
        emit(Insn(MOV_REG, dst = 2, src = FP))
        emit(Insn(ADD_IMM, dst = 2, imm = -16))
        emit(Insn(CALL, imm = H_LOOKUP))
        val noCpuCounter = emit(Insn(JEQ_IMM, dst = 0))
        emit(Insn(MOV_IMM, dst = 5, imm = 1))
        emit(Insn(ATOMIC_ADD_DW, dst = 0, src = 5))
        val restoreDescriptorCounter = program.size
        emit(Insn(LDX_DW, dst = 7, src = FP, off = -24))
        program[noCpuCounter] = program[noCpuCounter].copy(off = (restoreDescriptorCounter - noCpuCounter - 1).toShort())

        val incrementedOpenat2: Int?
        val isOpenat: Int?
        val incrementOpenat: Int?
        if (descriptorCounts) {
            emit(Insn(MOV_IMM, dst = 5, imm = 1))
            isOpenat = emit(Insn(JEQ_IMM, dst = 6))
            emit(Insn(ATOMIC_ADD_DW, dst = 7, src = 5, off = RAW_AUDIT_OPENAT2_OFFSET.toShort()))
            incrementedOpenat2 = emit(Insn(JA))
            incrementOpenat = program.size
            emit(Insn(ATOMIC_ADD_DW, dst = 7, src = 5, off = RAW_AUDIT_OPENAT_OFFSET.toShort()))
        } else {
            incrementedOpenat2 = null
            isOpenat = null
            incrementOpenat = null
        }
        val complete = emit(Insn(JA))
        val noSessionHandler = program.size
        emit(Insn(MOV_IMM, dst = 5, imm = 1))
        emit(Insn(ATOMIC_ADD_DW, dst = 7, src = 5, off = RAW_AUDIT_NO_SESSION_OFFSET.toShort()))
        val completedNoSession = emit(Insn(JA))
        val wrongTargetHandler = program.size
        emit(Insn(MOV_IMM, dst = 5, imm = 1))
        emit(Insn(ATOMIC_ADD_DW, dst = 7, src = 5, off = RAW_AUDIT_WRONG_TARGET_OFFSET.toShort()))
        val completedWrongTarget = emit(Insn(JA))

        val exit = program.size
        emit(Insn(MOV_IMM, dst = 0, imm = 0))
        emit(Insn(EXIT))
        val rawArgumentReadFailureHandler = if (rawTracepoint) {
            program.size.also {
                loadMap(1, 2)
                emit(Insn(MOV_IMM, dst = 5, imm = RAW_AUDIT_ARGUMENT_READ_FAILURE_COUNTER_INDEX))
                emit(Insn(STX_W, dst = FP, src = 5, off = -4))
                emit(Insn(MOV_REG, dst = 2, src = FP))
                emit(Insn(ADD_IMM, dst = 2, imm = -4))
                emit(Insn(CALL, imm = H_LOOKUP))
                val missingCounter = emit(Insn(JEQ_IMM, dst = 0))
                emit(Insn(MOV_IMM, dst = 5, imm = 1))
                emit(Insn(ATOMIC_ADD_DW, dst = 0, src = 5))
                val counted = emit(Insn(JA))
                program[missingCounter] = program[missingCounter].copy(off = (exit - missingCounter - 1).toShort())
                program[counted] = program[counted].copy(off = (exit - counted - 1).toShort())
            }
        } else {
            null
        }
        listOfNotNull(nonOpenatFamily, undeclaredDescriptor, incrementedOpenat2, complete, completedNoSession, completedWrongTarget).forEach { index ->
            program[index] = program[index].copy(off = (exit - index - 1).toShort())
        }
        program[noSession] = program[noSession].copy(off = (noSessionHandler - noSession - 1).toShort())
        program[wrongTarget] = program[wrongTarget].copy(off = (wrongTargetHandler - wrongTarget - 1).toShort())
        if (isOpenat != null && incrementOpenat != null) {
            program[isOpenat] = program[isOpenat].copy(off = (incrementOpenat - isOpenat - 1).toShort())
        }
        rawArgumentReadFailure?.let { index ->
            program[index] = program[index].copy(off = (requireNotNull(rawArgumentReadFailureHandler) - index - 1).toShort())
        }
        return program.toList()
    }

    /**
     * Builds the tracepoint/raw_syscalls/sys_enter eBPF program for capturing syscall events with active invocation state.
     */
    public fun buildSysEnterProgram(options: TierEOptions = TierEOptions()): List<Insn> = buildSysEnterProgram(options, rawTracepoint = false)

    /** x86_64 raw-tracepoint program with the same 88-byte ring record. */
    public fun buildRawTracepointSysEnterProgram(options: TierEOptions = TierEOptions()): List<Insn> = buildSysEnterProgram(options, rawTracepoint = true)

    private fun buildSysEnterProgram(
        options: TierEOptions,
        rawTracepoint: Boolean,
    ): List<Insn> {
        val p = mutableListOf<Insn>()
        val kernelCoverageMode =
            if (options.emissionMode == TierEEmissionMode.UNIQUE_STACK_SYSCALL) options.kernelCoverageMode else TierEKernelCoverageMode.NONE

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

        fun loadSyscallNumber(destination: Int = 5) {
            // Both raw tracepoint and typed tracepoint contexts have id at +8.
            emit(Insn(LDX_DW, dst = destination, src = 9, off = 8))
        }

        fun loadArgument(index: Int) {
            if (!rawTracepoint) {
                emit(Insn(LDX_DW, dst = 5, src = 9, off = (16 + index * 8).toShort()))
                return
            }
            emit(Insn(LDX_DW, dst = 3, src = 9, off = 0)) // raw args[0] = pt_regs *
            emit(Insn(ADD_IMM, dst = 3, imm = X86_64_SYSCALL_ARGUMENT_OFFSETS[index]))
            emit(Insn(MOV_REG, dst = 1, src = FP))
            emit(Insn(ADD_IMM, dst = 1, imm = -96))
            emit(Insn(MOV_IMM, dst = 2, imm = 8))
            emit(Insn(CALL, imm = H_PROBE_READ_KERNEL))
            emit(Insn(LDX_DW, dst = 5, src = FP, off = -96))
        }

        fun emitAuditFailureCounter(counterIndex: Int) {
            emit(Insn(MOV_IMM, dst = 5, imm = counterIndex))
            emit(Insn(STX_W, dst = FP, src = 5, off = -120))
            ldMap(1, COUNTER_MAP_INDEX)
            emit(Insn(MOV_REG, dst = 2, src = FP))
            emit(Insn(ADD_IMM, dst = 2, imm = -120))
            emit(Insn(CALL, imm = H_LOOKUP))
            val missingCounter = emit(Insn(JEQ_IMM, dst = 0))
            emit(Insn(MOV_IMM, dst = 5, imm = 1))
            emit(Insn(ATOMIC_ADD_DW, dst = 0, src = 5))
            val complete = p.size
            p[missingCounter] = p[missingCounter].copy(off = (complete - missingCounter - 1).toShort())
        }

        fun emitExactAdmissionAuditCounter(offset: Int) {
            if (!options.benchmarkExactAdmissionAudit) return
            emit(Insn(LDX_W, dst = 5, src = FP, off = -100))
            val notEligible = emit(Insn(JEQ_IMM, dst = 5))
            ldMap(1, EXACT_ADMISSION_AUDIT_MAP_INDEX)
            emit(Insn(MOV_REG, dst = 2, src = FP))
            emit(Insn(ADD_IMM, dst = 2, imm = -88))
            emit(Insn(CALL, imm = H_LOOKUP))
            val missingEntry = emit(Insn(JEQ_IMM, dst = 0))
            emit(Insn(MOV_IMM, dst = 5, imm = 1))
            emit(Insn(ATOMIC_ADD_DW, dst = 0, src = 5, off = offset.toShort()))
            val counted = emit(Insn(JA))
            val missingEntryHandler = p.size
            emitAuditFailureCounter(EXACT_ADMISSION_AUDIT_LOOKUP_FAILURE_COUNTER_INDEX)
            val complete = p.size
            p[notEligible] = p[notEligible].copy(off = (complete - notEligible - 1).toShort())
            p[missingEntry] = p[missingEntry].copy(off = (missingEntryHandler - missingEntry - 1).toShort())
            p[counted] = p[counted].copy(off = (complete - counted - 1).toShort())
        }

        emit(Insn(MOV_REG, dst = 9, src = 1))
        emit(Insn(CALL, imm = H_PID_TGID))
        emit(Insn(MOV_REG, dst = 8, src = 0))
        emit(Insn(MOV_IMM, dst = 5, imm = 0))
        emit(Insn(STX_W, dst = FP, src = 5, off = -4))

        if (options.benchmarkExactAdmissionAudit) {
            emit(Insn(STX_W, dst = FP, src = 5, off = -100))
            loadSyscallNumber()
            val notOpenat = emit(Insn(JNE_IMM, dst = 5, imm = 257))
            val argumentReadFailure: Int?
            if (rawTracepoint) {
                emit(Insn(LDX_DW, dst = 3, src = 9, off = 0))
                emit(Insn(ADD_IMM, dst = 3, imm = X86_64_SYSCALL_ARGUMENT_OFFSETS[0]))
                emit(Insn(MOV_REG, dst = 1, src = FP))
                emit(Insn(ADD_IMM, dst = 1, imm = -96))
                emit(Insn(MOV_IMM, dst = 2, imm = 8))
                emit(Insn(CALL, imm = H_PROBE_READ_KERNEL))
                argumentReadFailure = emit(Insn(JNE_IMM, dst = 0))
                emit(Insn(LDX_DW, dst = 5, src = FP, off = -96))
            } else {
                argumentReadFailure = null
                emit(Insn(LDX_DW, dst = 5, src = 9, off = 16))
            }
            emit(Insn(STX_W, dst = FP, src = 5, off = -88))
            emit(Insn(MOV_IMM, dst = 5, imm = 1))
            emit(Insn(STX_W, dst = FP, src = 5, off = -100))
            emitExactAdmissionAuditCounter(EXACT_ADMISSION_AUDIT_HANDLER_ENTERED_OFFSET)
            val auditSetupComplete = if (rawTracepoint) emit(Insn(JA)) else null
            val readFailureHandler = if (rawTracepoint) {
                p.size.also { emitAuditFailureCounter(EXACT_ADMISSION_AUDIT_ARGUMENT_READ_FAILURE_COUNTER_INDEX) }
            } else {
                null
            }
            val afterAuditSetup = p.size
            p[notOpenat] = p[notOpenat].copy(off = (afterAuditSetup - notOpenat - 1).toShort())
            auditSetupComplete?.let { complete ->
                p[complete] = p[complete].copy(off = (afterAuditSetup - complete - 1).toShort())
            }
            argumentReadFailure?.let { failure ->
                p[failure] = p[failure].copy(off = (requireNotNull(readFailureHandler) - failure - 1).toShort())
            }
        }

        // Reject every process except the configured target before consulting
        // task storage. This is required because both the raw tracepoint and
        // dynamic uprobe attachment are system-wide.
        ldMap(1, 3)
        emit(Insn(MOV_REG, dst = 2, src = FP))
        emit(Insn(ADD_IMM, dst = 2, imm = -4))
        emit(Insn(CALL, imm = H_LOOKUP))
        val missingSession = emit(Insn(JEQ_IMM, dst = 0))
        emit(Insn(LDX_W, dst = 4, src = 0, off = 4))
        emit(Insn(MOV_REG, dst = 5, src = 8))
        emit(Insn(RSH_IMM, dst = 5, imm = 32))
        val wrongTarget = emit(Insn(JNE_REG, dst = 5, src = 4))
        emit(Insn(MOV_REG, dst = 7, src = 0))
        emitExactAdmissionAuditCounter(EXACT_ADMISSION_AUDIT_SESSION_MATCHED_OFFSET)

        // Count before reading marker attachment. Otherwise a handler could
        // read the old attached state, be preempted during detach, and start
        // producing only after userspace observed an empty counter.
        // bpf_map_lookup_elem returns through r0, so retain the session-map
        // value in callee-saved r7 until marker state has been read.
        emit(Insn(STX_DW, dst = FP, src = 7, off = -112))
        ldMap(1, IN_FLIGHT_MAP_INDEX)
        emit(Insn(MOV_REG, dst = 2, src = FP))
        emit(Insn(ADD_IMM, dst = 2, imm = -4))
        emit(Insn(CALL, imm = H_LOOKUP))
        val missingInFlightCounter = emit(Insn(JEQ_IMM, dst = 0))
        emit(Insn(MOV_IMM, dst = 5, imm = 1))
        emit(Insn(ATOMIC_ADD_DW, dst = 0, src = 5))
        emitExactAdmissionAuditCounter(EXACT_ADMISSION_AUDIT_IN_FLIGHT_ADMITTED_OFFSET)
        emit(Insn(LDX_DW, dst = 7, src = FP, off = -112))
        emit(Insn(LDX_W, dst = 5, src = 7, off = 8))
        val markerDetached = emit(Insn(JEQ_IMM, dst = 5))
        val markerAttached = emit(Insn(JA))

        // Active marker admission is the Tier E observation boundary. A
        // detached marker never reads task storage, so an old state cannot
        // leak into a later session.
        val activeMarkerObservation = p.size
        emitExactAdmissionAuditCounter(EXACT_ADMISSION_AUDIT_MARKER_ATTACHED_OFFSET)
        emit(Insn(LDX_DW, dst = 7, src = FP, off = -112))
        emit(Insn(LDX_W, dst = 5, src = 7, off = 12))
        emit(Insn(STX_W, dst = FP, src = 5, off = -36))
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
        emitExactAdmissionAuditCounter(EXACT_ADMISSION_AUDIT_TASK_STORAGE_AVAILABLE_OFFSET)
        emit(Insn(LDX_DW, dst = 5, src = 6, off = 0))
        emit(Insn(STX_DW, dst = FP, src = 5, off = -24))
        val inactiveInvocation = emit(Insn(JEQ_IMM, dst = 5))
        emitExactAdmissionAuditCounter(EXACT_ADMISSION_AUDIT_ACTIVE_INVOCATION_OFFSET)
        emit(Insn(LDX_DW, dst = 5, src = 6, off = 8))
        emit(Insn(STX_DW, dst = FP, src = 5, off = -16))
        emit(Insn(LDX_W, dst = 5, src = 6, off = 16))
        emit(Insn(STX_W, dst = FP, src = 5, off = -8))
        emit(Insn(LDX_W, dst = 5, src = 6, off = 20))
        emit(Insn(LDX_W, dst = 4, src = FP, off = -36))
        val staleAttachmentGeneration = emit(Insn(JNE_REG, dst = 5, src = 4))
        emitExactAdmissionAuditCounter(EXACT_ADMISSION_AUDIT_GENERATION_VALIDATED_OFFSET)

        // Diagnostic counts are keyed by active invocation, bounded by the
        // map's LRU capacity, and never participate in attribution.
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
        if (kernelCoverageMode != TierEKernelCoverageMode.NONE) {
            loadSyscallNumber()
            emit(Insn(STX_W, dst = FP, src = 5, off = -40))
            emit(Insn(LDX_DW, dst = 5, src = FP, off = -24))
            emit(Insn(STX_DW, dst = FP, src = 5, off = -56))
            emit(Insn(MOV_IMM, dst = 5, imm = 0))
            emit(Insn(STX_DW, dst = FP, src = 5, off = -48))
            emit(Insn(LDX_W, dst = 5, src = FP, off = -40))
            emit(Insn(STX_W, dst = FP, src = 5, off = -48))
        }

        emit(Insn(MOV_IMM, dst = 5, imm = EXACT_ADMISSION_COUNTER_INDEX))
        emit(Insn(STX_W, dst = FP, src = 5, off = -4))
        ldMap(1, COUNTER_MAP_INDEX)
        emit(Insn(MOV_REG, dst = 2, src = FP))
        emit(Insn(ADD_IMM, dst = 2, imm = -4))
        emit(Insn(CALL, imm = H_LOOKUP))
        val missingExactAdmissionCounter = emit(Insn(JEQ_IMM, dst = 0))
        emit(Insn(MOV_IMM, dst = 5, imm = 1))
        emit(Insn(ATOMIC_ADD_DW, dst = 0, src = 5))
        val emitTaskStorageUnavailableDiagnostic =
            (options.benchmarkEmitTaskStorageUnavailableEvents || options.benchmarkEmitMarkerLossEvents) &&
                options.ringSubmissionMode == TierERingSubmissionMode.ENABLED
        val emitMarkerLossDiagnostics =
            options.benchmarkEmitMarkerLossEvents && options.ringSubmissionMode == TierERingSubmissionMode.ENABLED
        val skipMarkerLossDiagnostics = if (emitTaskStorageUnavailableDiagnostic || emitMarkerLossDiagnostics) {
            emit(Insn(JA))
        } else {
            null
        }

        fun emitMarkerLossDiagnostic(
            counterIndex: Int,
            flag: Int,
        ): MarkerLossDiagnostic {
            // The unchanged 88-byte record carries the raw syscall arguments while zeroing
            // attribution. This is diagnostic-only and lets an operation oracle distinguish
            // marker-state loss from ring reservation or collector loss.
            val start = p.size
            ldMap(1, COUNTER_MAP_INDEX)
            emit(Insn(MOV_IMM, dst = 5, imm = counterIndex))
            emit(Insn(STX_W, dst = FP, src = 5, off = -4))
            emit(Insn(MOV_REG, dst = 2, src = FP))
            emit(Insn(ADD_IMM, dst = 2, imm = -4))
            emit(Insn(CALL, imm = H_LOOKUP))
            val missingCounter = emit(Insn(JEQ_IMM, dst = 0))
            emit(Insn(MOV_IMM, dst = 5, imm = 1))
            emit(Insn(ATOMIC_ADD_DW, dst = 0, src = 5))
            emit(Insn(MOV_IMM, dst = 5, imm = 0))
            emit(Insn(STX_DW, dst = FP, src = 5, off = -24))
            emit(Insn(STX_DW, dst = FP, src = 5, off = -16))
            emit(Insn(MOV_IMM, dst = 5, imm = flag))
            emit(Insn(STX_W, dst = FP, src = 5, off = -8))
            return MarkerLossDiagnostic(start, missingCounter, emit(Insn(JA)))
        }

        val taskStorageUnavailableDiagnostic = if (emitTaskStorageUnavailableDiagnostic) {
            emitMarkerLossDiagnostic(
                TASK_STORAGE_UNAVAILABLE_COUNTER_INDEX,
                SyscallInvocationEvent.FLAG_TASK_STORAGE_UNAVAILABLE,
            )
        } else {
            null
        }
        val markerDetachedDiagnostic = if (emitMarkerLossDiagnostics) {
            emitMarkerLossDiagnostic(
                MARKER_DETACHED_COUNTER_INDEX,
                SyscallInvocationEvent.FLAG_MARKER_DETACHED,
            )
        } else {
            null
        }
        val staleMarkerGenerationDiagnostic = if (emitMarkerLossDiagnostics) {
            emitMarkerLossDiagnostic(
                STALE_MARKER_GENERATION_COUNTER_INDEX,
                SyscallInvocationEvent.FLAG_STALE_MARKER_GENERATION,
            )
        } else {
            null
        }
        val inactiveInvocationDiagnostic = if (emitMarkerLossDiagnostics) {
            emitMarkerLossDiagnostic(
                AMBIENT_INACTIVE_COUNTER_INDEX,
                SyscallInvocationEvent.FLAG_INACTIVE_INVOCATION,
            )
        } else {
            null
        }
        val markerStateUnavailableDiagnostic = if (emitMarkerLossDiagnostics) {
            emitMarkerLossDiagnostic(
                MARKER_FAILURE_COUNTER_INDEX,
                SyscallInvocationEvent.FLAG_MARKER_STATE_UNAVAILABLE,
            )
        } else {
            null
        }
        val reserveStart = p.size
        skipMarkerLossDiagnostics?.let { index ->
            p[index] = p[index].copy(off = (reserveStart - index - 1).toShort())
        }
        listOfNotNull(
            taskStorageUnavailableDiagnostic,
            markerDetachedDiagnostic,
            staleMarkerGenerationDiagnostic,
            inactiveInvocationDiagnostic,
            markerStateUnavailableDiagnostic,
        ).forEach { diagnostic ->
            p[diagnostic.skipToReserve] = p[diagnostic.skipToReserve].copy(
                off = (reserveStart - diagnostic.skipToReserve - 1).toShort(),
            )
        }
        p[noMetricContext] = p[noMetricContext].copy(off = (reserveStart - noMetricContext - 1).toShort())
        p[missingExactAdmissionCounter] = p[missingExactAdmissionCounter].copy(off = (reserveStart - missingExactAdmissionCounter - 1).toShort())
        p[newMetricContext] = p[newMetricContext].copy(off = (metricInsert - newMetricContext - 1).toShort())
        p[metricCounted] = p[metricCounted].copy(off = (metricDone - metricCounted - 1).toShort())
        val reservationFailed: Int?
        var submitted: Int? = null
        if (options.ringSubmissionMode == TierERingSubmissionMode.BENCHMARK_DISABLED) {
            // This benchmark-only build intentionally preserves the target filter, active-marker
            // admission, task-storage lookup, diagnostic maps, and in-flight accounting. It omits
            // only ring reservation, payload construction, and submission, so it is never evidence.
            // Falling through retains unique-edge diagnostic coverage without leaving dead code.
            reservationFailed = null
        } else {
            ldMap(1, 1)
            emit(Insn(MOV_IMM, dst = 2, imm = 88))
            emit(Insn(MOV_IMM, dst = 3, imm = 0))
            emit(Insn(CALL, imm = H_RB_RESERVE))
            reservationFailed = emit(Insn(JEQ_IMM, dst = 0))
            emit(Insn(MOV_REG, dst = 7, src = 0))
            emitExactAdmissionAuditCounter(EXACT_ADMISSION_AUDIT_RING_RESERVED_OFFSET)
            emit(Insn(CALL, imm = H_KTIME))
            emit(Insn(STX_DW, dst = 7, src = 0, off = 0))
            emit(Insn(MOV_REG, dst = 5, src = 8))
            emit(Insn(RSH_IMM, dst = 5, imm = 32))
            emit(Insn(STX_W, dst = 7, src = 5, off = 8))
            emit(Insn(STX_W, dst = 7, src = 8, off = 12))
            loadSyscallNumber()
            emit(Insn(STX_W, dst = 7, src = 5, off = 16))
            emit(Insn(LDX_W, dst = 5, src = FP, off = -8))
            emit(Insn(STX_W, dst = 7, src = 5, off = 20))
            emit(Insn(LDX_DW, dst = 5, src = FP, off = -24))
            emit(Insn(STX_DW, dst = 7, src = 5, off = 24))
            emit(Insn(LDX_DW, dst = 5, src = FP, off = -16))
            emit(Insn(STX_DW, dst = 7, src = 5, off = 32))
            repeat(SyscallInvocationEvent.ARGUMENT_COUNT) { index ->
                loadArgument(index)
                emit(Insn(STX_DW, dst = 7, src = 5, off = (40 + index * 8).toShort()))
            }
        }
        if (kernelCoverageMode != TierEKernelCoverageMode.NONE) {
            // Do not turn a malformed observation into a covered edge. A later
            // syscall from the same stack must be allowed to provide the first
            // valid representative instead of being suppressed by this one.
            emit(Insn(LDX_DW, dst = 5, src = FP, off = -24))
            val noContextForUpdate = emit(Insn(JEQ_IMM, dst = 5))
            // Capture flags were already copied from task storage to this stack slot. Reading
            // them here avoids making diagnostic coverage depend on a ring payload existing.
            emit(Insn(LDX_W, dst = 5, src = FP, off = -8))
            val argumentsWereRead = emit(Insn(JEQ_IMM, dst = 5))
            val skipInvalidCoverageUpdate = emit(Insn(JA))
            val coverageUpdateStart = p.size
            ldMap(1, 4)
            emit(Insn(MOV_REG, dst = 2, src = FP))
            emit(Insn(ADD_IMM, dst = 2, imm = -56))
            emit(Insn(CALL, imm = H_LOOKUP))
            if (kernelCoverageMode == TierEKernelCoverageMode.DIAGNOSTIC_UPDATE) {
                ldMap(1, 4)
                emit(Insn(MOV_REG, dst = 2, src = FP))
                emit(Insn(ADD_IMM, dst = 2, imm = -56))
                emit(Insn(MOV_IMM, dst = 5, imm = 1))
                emit(Insn(STX_DW, dst = FP, src = 5, off = -64))
                emit(Insn(MOV_REG, dst = 3, src = FP))
                emit(Insn(ADD_IMM, dst = 3, imm = -64))
                emit(Insn(MOV_IMM, dst = 4, imm = 0))
                emit(Insn(CALL, imm = H_UPDATE))
            }
            val afterCoverageUpdate = p.size
            p[argumentsWereRead] = p[argumentsWereRead].copy(off = (coverageUpdateStart - argumentsWereRead - 1).toShort())
            p[noContextForUpdate] = p[noContextForUpdate].copy(off = (afterCoverageUpdate - noContextForUpdate - 1).toShort())
            p[skipInvalidCoverageUpdate] = p[skipInvalidCoverageUpdate].copy(off = (afterCoverageUpdate - skipInvalidCoverageUpdate - 1).toShort())
        }
        if (options.ringSubmissionMode == TierERingSubmissionMode.ENABLED) {
            emitExactAdmissionAuditCounter(EXACT_ADMISSION_AUDIT_SUBMITTED_OFFSET)
            emit(Insn(MOV_REG, dst = 1, src = 7))
            emit(Insn(MOV_IMM, dst = 2, imm = 0))
            emit(Insn(CALL, imm = H_RB_SUBMIT))
            submitted = emit(Insn(JA))
        }
        val lossHandler: Int?
        val missingLossCounter: Int?
        if (options.ringSubmissionMode == TierERingSubmissionMode.ENABLED) {
            lossHandler = p.size
            emit(Insn(MOV_IMM, dst = 5, imm = 0))
            emit(Insn(STX_W, dst = FP, src = 5, off = -4))
            ldMap(1, 2)
            emit(Insn(MOV_REG, dst = 2, src = FP))
            emit(Insn(ADD_IMM, dst = 2, imm = -4))
            emit(Insn(CALL, imm = H_LOOKUP))
            missingLossCounter = emit(Insn(JEQ_IMM, dst = 0))
            emit(Insn(MOV_IMM, dst = 5, imm = 1))
            emit(Insn(ATOMIC_ADD_DW, dst = 0, src = 5))
        } else {
            lossHandler = null
            missingLossCounter = null
        }
        val completeActive = p.size
        ldMap(1, IN_FLIGHT_MAP_INDEX)
        emit(Insn(MOV_REG, dst = 2, src = FP))
        emit(Insn(ADD_IMM, dst = 2, imm = -4))
        emit(Insn(CALL, imm = H_LOOKUP))
        val missingCompletionCounter = emit(Insn(JEQ_IMM, dst = 0))
        emit(Insn(MOV_IMM, dst = 5, imm = -1))
        emit(Insn(ATOMIC_ADD_DW, dst = 0, src = 5))
        val activeExit = p.size
        emit(Insn(MOV_IMM, dst = 0, imm = 0))
        emit(Insn(EXIT))

        fun emitCounterAndComplete(counterIndex: Int) {
            ldMap(1, COUNTER_MAP_INDEX)
            emit(Insn(MOV_IMM, dst = 5, imm = counterIndex))
            emit(Insn(STX_W, dst = FP, src = 5, off = -4))
            emit(Insn(MOV_REG, dst = 2, src = FP))
            emit(Insn(ADD_IMM, dst = 2, imm = -4))
            emit(Insn(CALL, imm = H_LOOKUP))
            val missingCounter = emit(Insn(JEQ_IMM, dst = 0))
            emit(Insn(MOV_IMM, dst = 5, imm = 1))
            emit(Insn(ATOMIC_ADD_DW, dst = 0, src = 5))
            val complete = emit(Insn(JA))
            p[missingCounter] = p[missingCounter].copy(off = (completeActive - missingCounter - 1).toShort())
            p[complete] = p[complete].copy(off = (completeActive - complete - 1).toShort())
        }
        val markerFailureHandler = if (markerStateUnavailableDiagnostic == null) {
            p.size.also { emitCounterAndComplete(MARKER_FAILURE_COUNTER_INDEX) }
        } else {
            null
        }
        val ambientInactiveHandler = if (inactiveInvocationDiagnostic == null) {
            p.size.also { emitCounterAndComplete(AMBIENT_INACTIVE_COUNTER_INDEX) }
        } else {
            null
        }
        val markerDetachedHandler = if (markerDetachedDiagnostic == null) {
            p.size.also { emitCounterAndComplete(MARKER_DETACHED_COUNTER_INDEX) }
        } else {
            null
        }
        val taskStorageUnavailableHandler = if (taskStorageUnavailableDiagnostic == null) {
            p.size.also { emitCounterAndComplete(TASK_STORAGE_UNAVAILABLE_COUNTER_INDEX) }
        } else {
            null
        }
        val staleMarkerGenerationHandler = if (staleMarkerGenerationDiagnostic == null) {
            p.size.also { emitCounterAndComplete(STALE_MARKER_GENERATION_COUNTER_INDEX) }
        } else {
            null
        }
        // Map descriptors are fixed for the program lifetime, but if lookup
        // nevertheless fails we cannot prove this handler was counted. Mark
        // the run incomplete without executing the completion decrement.
        val inFlightCounterFailureHandler = p.size
        ldMap(1, COUNTER_MAP_INDEX)
        emit(Insn(MOV_IMM, dst = 5, imm = 0))
        emit(Insn(STX_W, dst = FP, src = 5, off = -4))
        emit(Insn(MOV_REG, dst = 2, src = FP))
        emit(Insn(ADD_IMM, dst = 2, imm = -4))
        emit(Insn(CALL, imm = H_LOOKUP))
        val missingLossCounterForInFlightFailure = emit(Insn(JEQ_IMM, dst = 0))
        emit(Insn(MOV_IMM, dst = 5, imm = 1))
        emit(Insn(ATOMIC_ADD_DW, dst = 0, src = 5))
        val finishInFlightFailure = emit(Insn(JA))
        val inFlightFailureExit = p.size
        emit(Insn(MOV_IMM, dst = 0, imm = 0))
        emit(Insn(EXIT))

        fun emitExactAdmissionAuditRejection(
            reasonOffset: Int,
            continuation: Int,
        ): Int {
            if (!options.benchmarkExactAdmissionAudit) return continuation
            val start = p.size
            emitExactAdmissionAuditCounter(reasonOffset)
            val complete = emit(Insn(JA))
            p[complete] = p[complete].copy(off = (continuation - complete - 1).toShort())
            return start
        }

        val markerFailureTarget = markerStateUnavailableDiagnostic?.start ?: requireNotNull(markerFailureHandler)
        val taskStorageTarget = taskStorageUnavailableDiagnostic?.start ?: requireNotNull(taskStorageUnavailableHandler)
        val inactiveTarget = inactiveInvocationDiagnostic?.start ?: requireNotNull(ambientInactiveHandler)
        val staleGenerationTarget = staleMarkerGenerationDiagnostic?.start ?: requireNotNull(staleMarkerGenerationHandler)
        val detachedTarget = markerDetachedDiagnostic?.start ?: requireNotNull(markerDetachedHandler)
        val missingSessionAuditTarget = emitExactAdmissionAuditRejection(EXACT_ADMISSION_AUDIT_SESSION_UNAVAILABLE_OFFSET, activeExit)
        val wrongTargetAuditTarget = emitExactAdmissionAuditRejection(EXACT_ADMISSION_AUDIT_WRONG_TARGET_OFFSET, activeExit)
        val missingInFlightAuditTarget = emitExactAdmissionAuditRejection(EXACT_ADMISSION_AUDIT_IN_FLIGHT_UNAVAILABLE_OFFSET, inFlightCounterFailureHandler)
        val noTaskAuditTarget = emitExactAdmissionAuditRejection(EXACT_ADMISSION_AUDIT_MARKER_STATE_UNAVAILABLE_OFFSET, markerFailureTarget)
        val noStorageAuditTarget = emitExactAdmissionAuditRejection(EXACT_ADMISSION_AUDIT_TASK_STORAGE_UNAVAILABLE_OFFSET, taskStorageTarget)
        val inactiveAuditTarget = emitExactAdmissionAuditRejection(EXACT_ADMISSION_AUDIT_INACTIVE_INVOCATION_OFFSET, inactiveTarget)
        val staleAuditTarget = emitExactAdmissionAuditRejection(EXACT_ADMISSION_AUDIT_STALE_GENERATION_OFFSET, staleGenerationTarget)
        val detachedAuditTarget = emitExactAdmissionAuditRejection(EXACT_ADMISSION_AUDIT_MARKER_DETACHED_OFFSET, detachedTarget)
        val reservationAuditTarget = emitExactAdmissionAuditRejection(
            EXACT_ADMISSION_AUDIT_RING_RESERVATION_FAILED_OFFSET,
            lossHandler ?: activeExit,
        )

        reservationFailed?.let { index ->
            p[index] = p[index].copy(off = (reservationAuditTarget - index - 1).toShort())
        }
        p[missingSession] = p[missingSession].copy(off = (missingSessionAuditTarget - missingSession - 1).toShort())
        p[wrongTarget] = p[wrongTarget].copy(off = (wrongTargetAuditTarget - wrongTarget - 1).toShort())
        p[noTask] = p[noTask].copy(
            off = (noTaskAuditTarget - noTask - 1).toShort(),
        )
        p[noStorage] = p[noStorage].copy(
            off = (noStorageAuditTarget - noStorage - 1).toShort(),
        )
        p[inactiveInvocation] = p[inactiveInvocation].copy(
            off = (inactiveAuditTarget - inactiveInvocation - 1).toShort(),
        )
        p[staleAttachmentGeneration] = p[staleAttachmentGeneration].copy(
            off = (staleAuditTarget - staleAttachmentGeneration - 1).toShort(),
        )
        p[markerDetached] = p[markerDetached].copy(
            off = (detachedAuditTarget - markerDetached - 1).toShort(),
        )
        p[markerAttached] = p[markerAttached].copy(off = (activeMarkerObservation - markerAttached - 1).toShort())
        submitted?.let { index -> p[index] = p[index].copy(off = (completeActive - index - 1).toShort()) }
        missingLossCounter?.let { index ->
            p[index] = p[index].copy(off = (completeActive - index - 1).toShort())
        }
        p[missingInFlightCounter] = p[missingInFlightCounter].copy(off = (missingInFlightAuditTarget - missingInFlightCounter - 1).toShort())
        listOfNotNull(
            taskStorageUnavailableDiagnostic,
            markerDetachedDiagnostic,
            staleMarkerGenerationDiagnostic,
            inactiveInvocationDiagnostic,
            markerStateUnavailableDiagnostic,
        ).forEach { diagnostic ->
            p[diagnostic.missingCounter] = p[diagnostic.missingCounter].copy(
                off = (inFlightCounterFailureHandler - diagnostic.missingCounter - 1).toShort(),
            )
        }
        p[missingCompletionCounter] = p[missingCompletionCounter].copy(off = (activeExit - missingCompletionCounter - 1).toShort())
        p[missingLossCounterForInFlightFailure] = p[missingLossCounterForInFlightFailure].copy(off = (inFlightFailureExit - missingLossCounterForInFlightFailure - 1).toShort())
        p[finishInFlightFailure] = p[finishInFlightFailure].copy(off = (inFlightFailureExit - finishInFlightFailure - 1).toShort())
        exits.forEach { index -> p[index] = p[index].copy(off = (activeExit - index - 1).toShort()) }

        return p.toList()
    }

    private data class MarkerLossDiagnostic(
        val start: Int,
        val missingCounter: Int,
        val skipToReserve: Int,
    )

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
        emit(Insn(LDX_W, dst = 5, src = 0, off = 12))
        emit(Insn(STX_W, dst = FP, src = 5, off = -44))
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
        emit(Insn(LDX_W, dst = 5, src = FP, off = -44))
        emit(Insn(STX_W, dst = 0, src = 5, off = 20))
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

    /**
     * Clears task-local marker state after the kernel has committed an exec transition.
     *
     * This attaches to `sched_process_exec`, rather than execve entry: failed exec attempts
     * retain the original image and may safely continue their active native scope.
     */
    public fun buildProcessExecProgram(): List<Insn> {
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

        emit(Insn(CALL, imm = H_PID_TGID))
        emit(Insn(MOV_REG, dst = 7, src = 0))
        emit(Insn(MOV_IMM, dst = 5, imm = 0))
        emit(Insn(STX_W, dst = FP, src = 5, off = -4))
        loadMap(1, 2)
        emit(Insn(MOV_REG, dst = 2, src = FP))
        emit(Insn(ADD_IMM, dst = 2, imm = -4))
        emit(Insn(CALL, imm = H_LOOKUP))
        val missingSession = emit(Insn(JEQ_IMM, dst = 0))
        emit(Insn(LDX_W, dst = 5, src = 0, off = 4))
        emit(Insn(MOV_REG, dst = 6, src = 7))
        emit(Insn(RSH_IMM, dst = 6, imm = 32))
        val wrongTarget = emit(Insn(JNE_REG, dst = 6, src = 5))
        loadMap(6, 0)
        emit(Insn(CALL, imm = H_CURRENT_TASK_BTF))
        val missingTask = emit(Insn(JEQ_IMM, dst = 0))
        emit(Insn(MOV_REG, dst = 1, src = 6))
        emit(Insn(MOV_REG, dst = 2, src = 0))
        emit(Insn(MOV_IMM, dst = 3, imm = 0))
        emit(Insn(MOV_IMM, dst = 4, imm = 0))
        emit(Insn(CALL, imm = H_TASK_STORAGE_GET))
        val missingStorage = emit(Insn(JEQ_IMM, dst = 0))
        emit(Insn(MOV_IMM, dst = 5, imm = 0))
        emit(Insn(STX_DW, dst = 0, src = 5, off = 0))
        emit(Insn(STX_DW, dst = 0, src = 5, off = 8))
        emit(Insn(STX_W, dst = 0, src = 5, off = 16))
        emit(Insn(STX_W, dst = 0, src = 5, off = 20))
        emit(Insn(MOV_IMM, dst = 0, imm = 0))
        emit(Insn(EXIT))
        val exit = program.lastIndex
        listOf(missingSession, wrongTarget, missingTask, missingStorage).forEach { branch ->
            program[branch] = program[branch].copy(off = (exit - branch - 1).toShort())
        }
        return program
    }
}
