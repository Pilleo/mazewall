package io.mazewall.profiler.tierE.engine

import io.mazewall.LinuxNative.SyscallResult
import io.mazewall.RawSyscallOperations
import io.mazewall.core.NativeArg
import io.mazewall.core.NativeArg.MemoryArg
import io.mazewall.ffi.memory.ConfinedSegment
import io.mazewall.profiler.attribution.TierEEmissionMode
import io.mazewall.profiler.attribution.TierEOptions
import io.mazewall.profiler.tierE.ringbuf.BpfRingBufferReader
import io.mazewall.profiler.tierE.ringbuf.SyscallInvocationEvent
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pure-Kotlin eBPF engine for Tier E context attribution.
 *
 * Loads a raw_tp/sys_enter program plus HASH/RINGBUF/ARRAY maps entirely
 * through bpf(2) syscalls via FFM downcalls. No libbpf, no C shim,
 * no marker library.
 */
public class TierEbpfEngine(
    private val native: RawSyscallOperations,
) : AutoCloseable {
    public companion object {
        private const val SYS_BPF: Long = 321
        private const val SYS_CLOSE: Long = 3
        private const val SYS_IOCTL: Long = 16
        private const val SYS_PERF_EVENT_OPEN: Long = 298
        private const val PERF_FLAG_FD_CLOEXEC: Long = 8
        private const val PERF_EVENT_IOC_ENABLE: Long = 0x2400
        private const val PERF_EVENT_IOC_SET_BPF: Long = 0x40042408

        private const val BPF_MAP_CREATE: Long = 0
        private const val BPF_MAP_UPDATE_ELEM: Long = 2
        private const val BPF_PROG_LOAD: Long = 5
        private const val BPF_RAW_TRACEPOINT_OPEN: Long = 17
        private const val BPF_BTF_LOAD: Long = 18

        private const val MAP_ARRAY: Int = 2
        private const val MAP_LRU_HASH: Int = 9
        private const val MAP_RINGBUF: Int = 27
        private const val MAP_TASK_STORAGE: Int = 29
        private const val PROG_RAW_TP: Int = 17
        private const val PROG_KPROBE: Int = 2
        private const val BPF_F_NO_PREALLOC: Int = 1

        /** Atomic add-and-store opcode: lock *(u64*)(dst+off) += src. */
        private const val ATOMIC_ADD_DW: Int = 0xdb

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
        private const val AND_IMM: Int = 0x57
        private const val OR_IMM: Int = 0x47
        private const val RSH_IMM: Int = 0x77
        private const val LD_MAP_FD: Int = 0x18
        private const val MOV_IMM: Int = 0xb7
        private const val MOV_REG: Int = 0xbf
        private const val CALL: Int = 0x85
        private const val EXIT: Int = 0x95

        private const val PSEUDO_FD: Int = 1
        private const val FP: Int = 10

        public const val EVENT_SIZE: Int = 88
        private const val RING_CAPACITY: Int = 1 shl 20
        private const val COVERAGE_EDGE_CAPACITY: Int = 1 shl 16
        private const val NAME_MAX: Int = 16
    }

    /** One eBPF instruction. */
    public data class Insn(
        val code: Int,
        val dst: Int = 0,
        val src: Int = 0,
        val off: Short = 0,
        val imm: Int = 0,
    )

    @Volatile private var invocationStorageFd: Int = -1

    @Volatile private var invocationBtfFd: Int = -1

    @Volatile private var ringFd: Int = -1

    @Volatile private var lossFd: Int = -1

    @Volatile private var sessionFd: Int = -1

    @Volatile private var coverageEdgeFd: Int = -1

    @Volatile private var progFd: Int = -1

    @Volatile private var markerProgFd: Int = -1
    private val markerPerfFds = mutableListOf<Int>()

    @Volatile private var linkFd: Int = -1
    private var ringReader: BpfRingBufferReader? = null
    private var options: TierEOptions = TierEOptions()

    public fun install(targetTgid: Int) {
        install(targetTgid, 1)
    }

    public fun install(
        targetTgid: Int,
        sessionTag: Int,
        options: TierEOptions = TierEOptions(),
    ) {
        require(System.getProperty("os.arch") in setOf("amd64", "x86_64")) {
            "Tier E register decoding currently supports x86_64 only"
        }
        require(targetTgid > 0)
        require(sessionTag > 0) { "session tag must be positive" }
        check(invocationStorageFd < 0) { "already installed" }
        this.options = options
        invocationBtfFd = loadBtf(InvocationStateBtf.load())
        invocationStorageFd = createMap(
            type = MAP_TASK_STORAGE,
            keySize = 4,
            valueSize = 24,
            maxEntries = 0,
            mapFlags = BPF_F_NO_PREALLOC,
            btfFd = invocationBtfFd,
            btfKeyTypeId = InvocationStateBtf.taskStorageKeyTypeId(),
            btfValueTypeId = InvocationStateBtf.invocationStateTypeId(),
        )
        ringFd = createMap(MAP_RINGBUF, keySize = 0, valueSize = 0, maxEntries = RING_CAPACITY)
        if (options.emissionMode == TierEEmissionMode.UNIQUE_STACK_SYSCALL) {
            coverageEdgeFd = createMap(MAP_LRU_HASH, keySize = 16, valueSize = 8, maxEntries = COVERAGE_EDGE_CAPACITY)
        }
        lossFd = createMap(MAP_ARRAY, keySize = 4, valueSize = 8, maxEntries = 4)
        sessionFd = createMap(MAP_ARRAY, keySize = 4, valueSize = 8, maxEntries = 1)
        Arena.ofConfined().use { arena ->
            val configuration = arena.allocate(8, 4)
            configuration.set(ValueLayout.JAVA_INT, 0, sessionTag)
            configuration.set(ValueLayout.JAVA_INT, 4, targetTgid)
            updateElem(arena, sessionFd, intSeg(arena, 0), configuration)
        }
        markerProgFd = loadProg(buildMarkerProgram(), listOf(invocationStorageFd, lossFd, sessionFd), PROG_KPROBE, "tier_e_marker")
        progFd = loadProg(
            buildProgram(),
            listOf(invocationStorageFd, ringFd, lossFd, sessionFd, coverageEdgeFd),
            PROG_RAW_TP,
            "tier_e_syscall",
        )
        linkFd = openRawTp(progFd, "sys_enter")
    }

    /** Number of kernel observations lost because the ring buffer was full. */
    public fun readLossCount(): Long = readCounter(0)

    /** Number of target marker transitions executed by the BPF program. */
    public fun readMarkerTransitionCount(): Long = readCounter(1)

    public fun readLastMarkerPidTgid(): Long = readCounter(2)

    public fun readMarkerFailureCount(): Long = readCounter(3)

    private fun readCounter(index: Int): Long =
        Arena.ofConfined().use { arena ->
        require(lossFd >= 0) { "engine is not installed" }
        val key = intSeg(arena, index)
        val value = arena.allocate(8, 8)
        val attr = arena.allocate(24)
        attr.set(ValueLayout.JAVA_INT, 0, lossFd)
        attr.set(ValueLayout.ADDRESS, 8, key)
        attr.set(ValueLayout.ADDRESS, 16, value)
        bpfCall(H_LOOKUP.toLong(), attr, 24)
        value.get(ValueLayout.JAVA_LONG, 0)
    }

    public fun attachMarker(
        targetPid: Int,
        agentLibrary: Path,
        markerOffset: Long,
    ) {
        require(markerProgFd >= 0) { "engine is not installed" }
        require(targetPid > 0 && markerOffset >= 0)
        require(Files.isRegularFile(agentLibrary)) { "agent library does not exist: $agentLibrary" }
        val uprobeType = Files.readString(Path.of("/sys/bus/event_source/devices/uprobe/type")).trim().toInt()
        Arena.ofConfined().use { arena ->
            val attr = arena.allocate(PerfEventAttrLayout.SIZE)
            attr.set(ValueLayout.JAVA_INT, PerfEventAttrLayout.TYPE, uprobeType)
            attr.set(ValueLayout.JAVA_INT, PerfEventAttrLayout.SIZE_FIELD, PerfEventAttrLayout.SIZE.toInt())
            attr.set(ValueLayout.ADDRESS, PerfEventAttrLayout.UPROBE_PATH, strSeg(arena, agentLibrary.toAbsolutePath().toString(), 4096))
            attr.set(ValueLayout.JAVA_LONG, PerfEventAttrLayout.UPROBE_OFFSET, markerOffset)
            // Dynamic uprobe PMUs use the single global event form implemented
            // by libbpf: pid=-1, cpu=0. Unlike ordinary hardware counters,
            // opening this local trace-uprobe event on every CPU duplicates
            // each probe hit. The BPF TGID guard isolates the target.
            for (cpu in onlineCpus().take(1)) {
                val fd = native
                    .syscall(
                    SYS_PERF_EVENT_OPEN,
                    MemoryArg(ConfinedSegment(attr)),
                    NativeArg.LongArg(-1),
                    NativeArg.LongArg(cpu.toLong()),
                    NativeArg.LongArg(-1),
                    NativeArg.LongArg(PERF_FLAG_FD_CLOEXEC),
                ).getOrThrow("perf_event_open(uprobe,cpu=$cpu)")
                    .toInt()
                markerPerfFds += fd
                rawIoctl(fd, PERF_EVENT_IOC_SET_BPF, markerProgFd.toLong())
                rawIoctl(fd, PERF_EVENT_IOC_ENABLE, 0)
            }
        }
    }

    /** Drains committed syscall records without waiting or timestamp correlation. */
    public fun drainEvents(): List<SyscallInvocationEvent> {
        require(ringFd >= 0) { "engine is not installed" }
        val reader = ringReader ?: BpfRingBufferReader(native, ringFd, RING_CAPACITY.toLong(), EVENT_SIZE).also {
            ringReader = it
        }
        return reader.drain().map(SyscallInvocationEvent::fromBytes)
    }

    override fun close() {
        var cleanupFailure: Throwable? = null
        try {
            ringReader?.close()
        } catch (failure: Throwable) {
            cleanupFailure = failure
        } finally {
            ringReader = null
        }
        markerPerfFds.forEach(::closeIfOpen)
        markerPerfFds.clear()
        closeIfOpen(linkFd)
        linkFd = -1
        closeIfOpen(progFd)
        progFd = -1
        closeIfOpen(markerProgFd)
        markerProgFd = -1
        closeIfOpen(ringFd)
        ringFd = -1
        closeIfOpen(lossFd)
        lossFd = -1
        closeIfOpen(sessionFd)
        sessionFd = -1
        closeIfOpen(coverageEdgeFd)
        coverageEdgeFd = -1
        closeIfOpen(invocationStorageFd)
        invocationStorageFd = -1
        closeIfOpen(invocationBtfFd)
        invocationBtfFd = -1
        cleanupFailure?.let { throw it }
    }

    // ── program construction ────────────────────────────────────────────────

    internal fun buildProgram(options: TierEOptions = this.options): List<Insn> {
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

        // Default to an explicit un-attributed observation. A missing marker is
        // evidence, not permission to hide the target syscall.
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

        // Coverage mode retains the first exact (canonical stack, syscall) edge.
        // A cache hit returns before a ring-buffer reservation; a miss is still
        // emitted if cache insertion later fails or the LRU has evicted an edge.
        var coverageMiss: Int? = null
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
            ldMap(1, 4)
            emit(Insn(MOV_REG, dst = 2, src = FP))
            emit(Insn(ADD_IMM, dst = 2, imm = -56))
            emit(Insn(CALL, imm = H_LOOKUP))
            coverageMiss = emit(Insn(JEQ_IMM, dst = 0))
            exits.add(emit(Insn(JA)))
        }

        val reserveStart = p.size
        coverageMiss?.let { p[it] = p[it].copy(off = (reserveStart - it - 1).toShort()) }
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
        p[submitted] = p[submitted].copy(off = (exitIndex - submitted - 2).toShort())
        p[missingLossCounter] = p[missingLossCounter].copy(off = (exitIndex - missingLossCounter - 1).toShort())
        exits.forEach { index -> p[index] = p[index].copy(off = (exitIndex - index - 1).toShort()) }

        return p.toList()
    }

    /** x86_64 uprobe program for `mazewall_stack_marker(uint64_t)`. */
    internal fun buildMarkerProgram(): List<Insn> {
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

    internal fun pack(i: Insn): Long {
        val code = i.code.toLong() and 0xff
        val regs = ((i.src and 0xF).toLong() shl 4) or (i.dst and 0xF).toLong()
        val off = (i.off.toLong() and 0xFFFF) shl 16
        val imm = (i.imm.toLong() and 0xFFFFFFFFL) shl 32
        return code or (regs shl 8) or off or imm
    }

    // ── bpf(2) wrappers ─────────────────────────────────────────────────────

    private fun bpfCall(
        cmd: Long,
        attr: MemorySegment,
        size: Long,
    ): Int {
        return when (val res = bpfResult(cmd, attr, size)) {
            is SyscallResult.Success -> res.value.toInt()
            is SyscallResult.Error -> res.throwErrno("bpf(cmd=$cmd)")
        }
    }

    private fun bpfResult(
        cmd: Long,
        attr: MemorySegment,
        size: Long,
    ) = native.syscall(
        SYS_BPF,
        NativeArg.LongArg(cmd),
        NativeArg.MemoryArg(ConfinedSegment(attr)),
        NativeArg.LongArg(size),
    )

    private fun createMap(
        type: Int,
        keySize: Int,
        valueSize: Int,
        maxEntries: Int,
        mapFlags: Int = 0,
        btfFd: Int = 0,
        btfKeyTypeId: Int = 0,
        btfValueTypeId: Int = 0,
    ): Int =
        Arena.ofConfined().use {
            val attr = it.allocate(72)
            attr.set(ValueLayout.JAVA_INT, 0, type)
            attr.set(ValueLayout.JAVA_INT, 4, keySize)
            attr.set(ValueLayout.JAVA_INT, 8, valueSize)
            attr.set(ValueLayout.JAVA_INT, 12, maxEntries)
            attr.set(ValueLayout.JAVA_INT, BpfMapCreateLayout.MAP_FLAGS, mapFlags)
            attr.set(ValueLayout.JAVA_INT, BpfMapCreateLayout.BTF_FD, btfFd)
            attr.set(ValueLayout.JAVA_INT, BpfMapCreateLayout.BTF_KEY_TYPE_ID, btfKeyTypeId)
            attr.set(ValueLayout.JAVA_INT, BpfMapCreateLayout.BTF_VALUE_TYPE_ID, btfValueTypeId)
            bpfCall(BPF_MAP_CREATE, attr, 72)
        }

    private fun loadBtf(bytes: ByteArray): Int =
        Arena.ofConfined().use {
        val btf = it.allocate(bytes.size.toLong())
        bytes.forEachIndexed { index, byte -> btf.set(ValueLayout.JAVA_BYTE, index.toLong(), byte) }
        val verifierLog = it.allocate(16 * 1024L)
        val attr = it.allocate(BpfBtfLoadLayout.SIZE)
        attr.set(ValueLayout.ADDRESS, BpfBtfLoadLayout.BTF, btf)
        attr.set(ValueLayout.ADDRESS, BpfBtfLoadLayout.LOG_BUFFER, verifierLog)
        attr.set(ValueLayout.JAVA_INT, BpfBtfLoadLayout.BTF_SIZE, bytes.size)
        attr.set(ValueLayout.JAVA_INT, BpfBtfLoadLayout.LOG_SIZE, verifierLog.byteSize().toInt())
        attr.set(ValueLayout.JAVA_INT, BpfBtfLoadLayout.LOG_LEVEL, 1)
        when (val result = bpfResult(BPF_BTF_LOAD, attr, BpfBtfLoadLayout.SIZE)) {
            is SyscallResult.Success -> result.value.toInt()
            is SyscallResult.Error -> error("BPF_BTF_LOAD failed: $result; verifier: ${verifierLog.getString(0).trim()}")
        }
    }

    private fun updateElem(
        a: Arena,
        fd: Int,
        key: MemorySegment,
        value: MemorySegment,
    ) {
        val attr = a.allocate(32)
        attr.set(ValueLayout.JAVA_INT, 0, fd)
        attr.set(ValueLayout.ADDRESS, 8, key)
        attr.set(ValueLayout.ADDRESS, 16, value)
        bpfCall(BPF_MAP_UPDATE_ELEM, attr, 32)
    }

    private fun loadProg(
        program: List<Insn>,
        mapFds: List<Int>,
        programType: Int,
        programName: String,
    ): Int {
        val insns = program.map { insn ->
            if (insn.code == LD_MAP_FD && insn.src == PSEUDO_FD) insn.copy(imm = mapFds[insn.imm]) else insn
        }
        return Arena.ofConfined().use {
            val packed = insns.map(::pack)
            val seg = it.allocate(packed.size * 8L, 8)
            packed.forEachIndexed { i, v -> seg.set(ValueLayout.JAVA_LONG, i * 8L, v) }

            val verifierLog = it.allocate(64 * 1024L)
            val attr = it.allocate(BpfProgLoadLayout.SIZE)
            attr.set(ValueLayout.JAVA_INT, BpfProgLoadLayout.PROGRAM_TYPE, programType)
            attr.set(ValueLayout.JAVA_INT, BpfProgLoadLayout.INSTRUCTION_COUNT, insns.size)
            attr.set(ValueLayout.ADDRESS, BpfProgLoadLayout.INSTRUCTIONS, seg)
            attr.set(ValueLayout.ADDRESS, BpfProgLoadLayout.LICENSE, strSeg(it, "GPL", 4))
            attr.set(ValueLayout.JAVA_INT, BpfProgLoadLayout.LOG_LEVEL, 1)
            attr.set(ValueLayout.JAVA_INT, BpfProgLoadLayout.LOG_SIZE, verifierLog.byteSize().toInt())
            attr.set(ValueLayout.ADDRESS, BpfProgLoadLayout.LOG_BUFFER, verifierLog)
            require(programName.length < BpfProgLoadLayout.PROGRAM_NAME_SIZE)
            programName.toByteArray(Charsets.US_ASCII).forEachIndexed { index, byte ->
                attr.set(ValueLayout.JAVA_BYTE, BpfProgLoadLayout.PROGRAM_NAME + index, byte)
            }
            when (val result = bpfResult(BPF_PROG_LOAD, attr, BpfProgLoadLayout.SIZE)) {
                is SyscallResult.Success -> result.value.toInt()
                is SyscallResult.Error -> {
                    val log = verifierLog.getString(0).trim()
                    val diagnostic = if (log.isEmpty()) "no verifier log" else log
                    throw IllegalStateException("BPF_PROG_LOAD failed: $result; verifier: $diagnostic")
                }
            }
        }
    }

    private fun openRawTp(
        progFd: Int,
        name: String,
    ): Int =
        Arena.ofConfined().use {
            val attr = it.allocate(BpfRawTracepointOpenLayout.SIZE)
            attr.set(ValueLayout.ADDRESS, BpfRawTracepointOpenLayout.NAME, strSeg(it, name, 64))
            attr.set(ValueLayout.JAVA_INT, BpfRawTracepointOpenLayout.PROGRAM_FD, progFd)
            bpfCall(BPF_RAW_TRACEPOINT_OPEN, attr, BpfRawTracepointOpenLayout.SIZE)
        }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun strSeg(
        a: Arena,
        s: String,
        size: Int,
    ): MemorySegment {
        val seg = a.allocate(size.toLong())
        s.toByteArray(Charsets.US_ASCII).forEachIndexed { i, b -> seg.set(ValueLayout.JAVA_BYTE, i.toLong(), b) }
        return seg
    }

    private fun intSeg(
        a: Arena,
        v: Int,
    ): MemorySegment {
        val s = a.allocate(4, 4)
        s.set(ValueLayout.JAVA_INT, 0, v)
        return s
    }

    private fun closeIfOpen(fd: Int) {
        if (fd >= 0) native.syscall(SYS_CLOSE, NativeArg.LongArg(fd.toLong()))
    }

    private fun onlineCpus(): List<Int> =
        Files
            .readString(Path.of("/sys/devices/system/cpu/online"))
        .trim()
        .split(',')
        .flatMap { range ->
            val endpoints = range.split('-', limit = 2).map(String::toInt)
            if (endpoints.size == 1) listOf(endpoints[0]) else (endpoints[0]..endpoints[1]).toList()
        }

    private fun rawIoctl(
        fd: Int,
        request: Long,
        argument: Long,
    ) {
        native
            .syscall(
            SYS_IOCTL,
            NativeArg.LongArg(fd.toLong()),
            NativeArg.LongArg(request),
            NativeArg.LongArg(argument),
        ).getOrThrow("ioctl(fd=$fd, request=$request)")
    }
}

/**
 * Native layout of the initial, stable portion of `union bpf_attr` for
 * `BPF_PROG_LOAD` on 64-bit Linux. The union contains unrelated commands at
 * these same offsets, so the program-load layout is intentionally explicit.
 */
internal object BpfProgLoadLayout {
    const val SIZE: Long = 104
    const val PROGRAM_TYPE: Long = 0
    const val INSTRUCTION_COUNT: Long = 4
    const val INSTRUCTIONS: Long = 8
    const val LICENSE: Long = 16
    const val LOG_LEVEL: Long = 24
    const val LOG_SIZE: Long = 28
    const val LOG_BUFFER: Long = 32
    const val KERNEL_VERSION: Long = 40
    const val PROGRAM_FLAGS: Long = 44
    const val PROGRAM_NAME: Long = 48
    const val PROGRAM_NAME_SIZE: Int = 16
}

/** Native layout of `union bpf_attr` for `BPF_RAW_TRACEPOINT_OPEN`. */
internal object BpfRawTracepointOpenLayout {
    const val SIZE: Long = 16
    const val NAME: Long = 0
    const val PROGRAM_FD: Long = 8
}

/** Native layout of `union bpf_attr` for `BPF_BTF_LOAD`. */
internal object BpfBtfLoadLayout {
    const val SIZE: Long = 40
    const val BTF: Long = 0
    const val LOG_BUFFER: Long = 8
    const val BTF_SIZE: Long = 16
    const val LOG_SIZE: Long = 20
    const val LOG_LEVEL: Long = 24
}

/** BTF-related fields in the `BPF_MAP_CREATE` arm of `union bpf_attr`. */
internal object BpfMapCreateLayout {
    const val MAP_FLAGS: Long = 16
    const val BTF_FD: Long = 48
    const val BTF_KEY_TYPE_ID: Long = 52
    const val BTF_VALUE_TYPE_ID: Long = 56
}

internal object PerfEventAttrLayout {
    const val SIZE: Long = 144
    const val TYPE: Long = 0
    const val SIZE_FIELD: Long = 4
    const val SAMPLE_PERIOD: Long = 16
    const val UPROBE_PATH: Long = 56
    const val UPROBE_OFFSET: Long = 64
}
