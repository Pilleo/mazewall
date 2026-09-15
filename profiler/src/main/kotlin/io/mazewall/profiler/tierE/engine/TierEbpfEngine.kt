package io.mazewall.profiler.tierE.engine

import io.mazewall.ffi.memory.ConfinedSegment
import io.mazewall.platform.native.LinuxNative.SyscallResult
import io.mazewall.platform.native.RawSyscallOperations
import io.mazewall.platform.syscall.NativeArg
import io.mazewall.profiler.internal.attribution.TierEEmissionMode
import io.mazewall.profiler.internal.attribution.TierEOptions
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
internal class TierEbpfEngine(
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
        private const val BPF_MAP_LOOKUP_ELEM: Long = 1
        private const val BPF_MAP_UPDATE_ELEM: Long = 2
        private const val BPF_MAP_GET_NEXT_KEY: Long = 4
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

        // eBPF helper IDs (uapi/linux/bpf.h)
        private const val H_LOOKUP: Int = 1

        public const val EVENT_SIZE: Int = 88
        private const val RING_CAPACITY: Int = 1 shl 20
        private const val COVERAGE_EDGE_CAPACITY: Int = 1 shl 16
        private const val CONTEXT_METRIC_CAPACITY: Int = 1 shl 14
        private const val SESSION_TAG_OFFSET: Long = 0
        private const val TARGET_TGID_OFFSET: Long = 4
        private const val SESSION_RESERVED_OFFSET: Long = 8
        private const val MARKER_FAILURE_COUNTER_INDEX = 3
        private const val BPF_ATTRIBUTE_FD_OFFSET: Long = 0
        private const val BPF_ATTRIBUTE_KEY_OFFSET: Long = 8
        private const val BPF_ATTRIBUTE_VALUE_OFFSET: Long = 16
    }

    /** One eBPF instruction. */
    internal data class Insn(
        val code: Int,
        val dst: Int = 0,
        val src: Int = 0,
        val off: Short = 0,
        val imm: Int = 0,
    )

    private enum class Lifecycle { DETACHED, INSTALLING, ATTACHED, CLOSING, CLOSED }

    /**
     * The complete ownership set for one installation. It is deliberately not
     * published until every acquisition succeeds, so a failed install has one
     * reverse-order rollback path instead of nine independently visible FDs.
     */
    private class Resources {
        var invocationStorageFd: Int = -1
        var invocationBtfFd: Int = -1
        var ringFd: Int = -1
        var lossFd: Int = -1
        var contextMetricFd: Int = -1
        var sessionFd: Int = -1
        var coverageEdgeFd: Int = -1
        var progFd: Int = -1
        var markerProgFd: Int = -1
        var linkFd: Int = -1
        val markerPerfFds = mutableListOf<Int>()
        var ringReader: BpfRingBufferReader? = null
    }

    private val lifecycleLock = Any()
    private var lifecycle = Lifecycle.DETACHED
    private var resources: Resources? = null
    private var options: TierEOptions = TierEOptions()

    public fun install(targetTgid: Int) {
        install(targetTgid, 1)
    }

    @Suppress("TooGenericExceptionCaught") // native acquisition and rollback must retain every failure type
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
        val installed = synchronized(lifecycleLock) {
            check(lifecycle == Lifecycle.DETACHED || lifecycle == Lifecycle.CLOSED) { "already installed or closing" }
            lifecycle = Lifecycle.INSTALLING
            Resources()
        }
        try {
            this.options = options
            installed.invocationBtfFd = loadBtf(InvocationStateBtf.load())
            installed.invocationStorageFd = createMap(
            type = MAP_TASK_STORAGE,
            keySize = 4,
            valueSize = 24,
            maxEntries = 0,
            mapFlags = BPF_F_NO_PREALLOC,
            btfFd = installed.invocationBtfFd,
            btfKeyTypeId = InvocationStateBtf.taskStorageKeyTypeId(),
            btfValueTypeId = InvocationStateBtf.invocationStateTypeId(),
        )
            installed.ringFd = createMap(MAP_RINGBUF, keySize = 0, valueSize = 0, maxEntries = RING_CAPACITY)
            if (options.emissionMode == TierEEmissionMode.UNIQUE_STACK_SYSCALL) {
                installed.coverageEdgeFd = createMap(MAP_LRU_HASH, keySize = 16, valueSize = 8, maxEntries = COVERAGE_EDGE_CAPACITY)
            }
            installed.lossFd = createMap(MAP_ARRAY, keySize = 4, valueSize = 8, maxEntries = 4)
            installed.contextMetricFd = createMap(MAP_LRU_HASH, keySize = 8, valueSize = 8, maxEntries = CONTEXT_METRIC_CAPACITY)
            installed.sessionFd = createMap(MAP_ARRAY, keySize = 4, valueSize = 16, maxEntries = 1)
            Arena.ofConfined().use { arena ->
            val configuration = arena.allocate(16, 4)
            configuration.set(ValueLayout.JAVA_INT, SESSION_TAG_OFFSET, sessionTag)
            configuration.set(ValueLayout.JAVA_INT, TARGET_TGID_OFFSET, targetTgid)
            configuration.set(ValueLayout.JAVA_INT, SESSION_RESERVED_OFFSET, 0)
                updateElem(arena, installed.sessionFd, intSeg(arena, 0), configuration)
            }
            installed.markerProgFd = loadProg(buildMarkerProgram(), listOf(installed.invocationStorageFd, installed.lossFd, installed.sessionFd), PROG_KPROBE, "tier_e_marker")
            installed.progFd = loadProg(
            buildProgram(),
            listOf(installed.invocationStorageFd, installed.ringFd, installed.lossFd, installed.sessionFd, installed.coverageEdgeFd, installed.contextMetricFd),
            PROG_RAW_TP,
            "tier_e_syscall",
        )
            installed.linkFd = openRawTp(installed.progFd, "sys_enter")
            synchronized(lifecycleLock) {
                resources = installed
                lifecycle = Lifecycle.ATTACHED
            }
        } catch (failure: Throwable) {
            try {
                closeResources(installed)
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            synchronized(lifecycleLock) { lifecycle = Lifecycle.DETACHED }
            throw failure
        }
    }

    /** Number of kernel observations lost because the ring buffer was full. */
    public fun readLossCount(): Long = readCounter(0)

    /** Number of target marker transitions executed by the BPF program. */
    public fun readMarkerTransitionCount(): Long = readCounter(1)

    public fun readLastMarkerPidTgid(): Long = readCounter(2)

    public fun readMarkerFailureCount(): Long = readCounter(MARKER_FAILURE_COUNTER_INDEX)

    /** Snapshot of bounded per-invocation event counts from the BPF metric map. */
    public fun readContextMetrics(): Map<Long, Long> =
        Arena.ofConfined().use { arena ->
            val installed = requireInstalled()
            val key = arena.allocate(8, 8)
            val nextKey = arena.allocate(8, 8)
            val value = arena.allocate(8, 8)
            val metrics = linkedMapOf<Long, Long>()
            var currentKey: MemorySegment? = null
            while (true) {
                val nextAttr = arena.allocate(32)
                nextAttr.set(ValueLayout.JAVA_INT, BPF_ATTRIBUTE_FD_OFFSET, installed.contextMetricFd)
                nextAttr.set(ValueLayout.ADDRESS, BPF_ATTRIBUTE_KEY_OFFSET, currentKey ?: MemorySegment.NULL)
                nextAttr.set(ValueLayout.ADDRESS, BPF_ATTRIBUTE_VALUE_OFFSET, nextKey)
                when (val result = bpfResult(BPF_MAP_GET_NEXT_KEY, nextAttr, 32)) {
                    is SyscallResult.Error -> {
                        if (result.errno == io.mazewall.ffi.NativeConstants.ENOENT) break
                        result.throwErrno("bpf(BPF_MAP_GET_NEXT_KEY)")
                    }
                    is SyscallResult.Success -> Unit
                }
                val contextId = nextKey.get(ValueLayout.JAVA_LONG, 0)
                key.set(ValueLayout.JAVA_LONG, 0, contextId)
                val lookupAttr = arena.allocate(32)
                lookupAttr.set(ValueLayout.JAVA_INT, BPF_ATTRIBUTE_FD_OFFSET, installed.contextMetricFd)
                lookupAttr.set(ValueLayout.ADDRESS, BPF_ATTRIBUTE_KEY_OFFSET, key)
                lookupAttr.set(ValueLayout.ADDRESS, BPF_ATTRIBUTE_VALUE_OFFSET, value)
                when (val result = bpfResult(BPF_MAP_LOOKUP_ELEM, lookupAttr, 32)) {
                    is SyscallResult.Success -> metrics[contextId] = value.get(ValueLayout.JAVA_LONG, 0)
                    is SyscallResult.Error -> if (result.errno != io.mazewall.ffi.NativeConstants.ENOENT) result.throwErrno("bpf(BPF_MAP_LOOKUP_ELEM)")
                }
                currentKey = key
            }
            metrics.toMap()
        }

    private fun readCounter(index: Int): Long =
        Arena.ofConfined().use { arena ->
        val installed = requireInstalled()
        val key = intSeg(arena, index)
        val value = arena.allocate(8, 8)
        val attr = arena.allocate(24)
        attr.set(ValueLayout.JAVA_INT, 0, installed.lossFd)
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
        val installed = requireInstalled()
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
                    NativeArg.MemoryArg(ConfinedSegment(attr)),
                    NativeArg.LongArg(-1),
                    NativeArg.LongArg(cpu.toLong()),
                    NativeArg.LongArg(-1),
                    NativeArg.LongArg(PERF_FLAG_FD_CLOEXEC),
                ).getOrThrow("perf_event_open(uprobe,cpu=$cpu)")
                    .toInt()
                installed.markerPerfFds += fd
                rawIoctl(fd, PERF_EVENT_IOC_SET_BPF, installed.markerProgFd.toLong())
                rawIoctl(fd, PERF_EVENT_IOC_ENABLE, 0)
            }
        }
        setMarkerAttached(installed, true)
    }

    /** Disables marker admission while keeping the selected sys_enter observer alive. */
    public fun detachMarker() {
        val installed = requireInstalled()
        setMarkerAttached(installed, false)
        // A failed close has an indeterminate descriptor state: do not retry a
        // numeric descriptor that the kernel may already have reused.
        val markerPerfFds = installed.markerPerfFds.toList()
        installed.markerPerfFds.clear()
        markerPerfFds.forEach(::closeIfOpen)
    }

    /** Drains committed syscall records without waiting or timestamp correlation. */
    public fun drainEvents(): List<SyscallInvocationEvent> {
        val installed = requireInstalled()
        val reader = installed.ringReader ?: BpfRingBufferReader(native, installed.ringFd, RING_CAPACITY.toLong(), EVENT_SIZE).also {
            installed.ringReader = it
        }
        return reader.drain().map(SyscallInvocationEvent::fromBytes)
    }

    @Suppress("TooGenericExceptionCaught") // Cleanup must preserve every native failure, including errors surfaced by an injected syscall seam.
    override fun close() {
        val installed = synchronized(lifecycleLock) {
            if (lifecycle == Lifecycle.CLOSED || lifecycle == Lifecycle.DETACHED) return
            lifecycle = Lifecycle.CLOSING
            requireNotNull(resources)
        }
        try {
            closeResources(installed)
        } catch (failure: Throwable) {
            synchronized(lifecycleLock) {
                // BPF mappings remain owned until their unmap succeeds, so a
                // later close may safely retry only that mapping cleanup.
                if (installed.ringReader != null) {
                    resources = installed
                    lifecycle = Lifecycle.CLOSING
                } else {
                    resources = null
                    lifecycle = Lifecycle.CLOSED
                }
            }
            throw failure
        }
        synchronized(lifecycleLock) {
            resources = null
            lifecycle = Lifecycle.CLOSED
        }
    }

    private fun requireInstalled(): Resources =
        synchronized(lifecycleLock) {
        check(lifecycle == Lifecycle.ATTACHED) { "engine is not installed" }
        requireNotNull(resources)
    }

    private fun setMarkerAttached(
        installed: Resources,
        attached: Boolean,
    ) {
        Arena.ofConfined().use { arena ->
            val key = intSeg(arena, 0)
            val current = arena.allocate(16, 4)
            val value = arena.allocate(16, 4)
            val lookup = arena.allocate(32)
            lookup.set(ValueLayout.JAVA_INT, 0, installed.sessionFd)
            lookup.set(ValueLayout.ADDRESS, 8, key)
            lookup.set(ValueLayout.ADDRESS, 16, current)
            bpfCall(BPF_MAP_LOOKUP_ELEM, lookup, 32)
            value.copyFrom(current)
            value.set(ValueLayout.JAVA_INT, 8, if (attached) 1 else 0)
            updateElem(arena, installed.sessionFd, key, value)
        }
    }

    @Suppress("TooGenericExceptionCaught") // close is best-effort across independently owned native resources
    private fun closeResources(installed: Resources) {
        var cleanupFailure: Throwable? = null
        try {
            installed.ringReader?.close()
            installed.ringReader = null
        } catch (failure: Throwable) {
            cleanupFailure = failure
        }

        fun close(
            fd: Int,
            clear: () -> Unit,
        ) {
            try {
                closeIfOpen(fd)
            } catch (failure: Throwable) {
                cleanupFailure?.addSuppressed(failure) ?: run { cleanupFailure = failure }
            } finally {
                // Never retry close(2): on Linux an error may still mean the
                // descriptor was released and subsequently reused.
                clear()
            }
        }
        val markerPerfFds = installed.markerPerfFds.toList()
        installed.markerPerfFds.clear()
        markerPerfFds.asReversed().forEach { close(it) {} }
        close(installed.linkFd) { installed.linkFd = -1 }
        close(installed.progFd) { installed.progFd = -1 }
        close(installed.markerProgFd) { installed.markerProgFd = -1 }
        close(installed.ringFd) { installed.ringFd = -1 }
        close(installed.lossFd) { installed.lossFd = -1 }
        close(installed.contextMetricFd) { installed.contextMetricFd = -1 }
        close(installed.sessionFd) { installed.sessionFd = -1 }
        close(installed.coverageEdgeFd) { installed.coverageEdgeFd = -1 }
        close(installed.invocationStorageFd) { installed.invocationStorageFd = -1 }
        close(installed.invocationBtfFd) { installed.invocationBtfFd = -1 }
        cleanupFailure?.let { throw it }
    }

    // ── program construction ────────────────────────────────────────────────

    internal fun buildProgram(options: TierEOptions = this.options): List<Insn> = TierEBpfProgramBuilder.buildSysEnterProgram(options)

    /** x86_64 uprobe program for `mazewall_stack_marker(uint64_t)`. */
    internal fun buildMarkerProgram(): List<Insn> = TierEBpfProgramBuilder.buildMarkerProgram()

    internal fun pack(i: Insn): Long = BpfProgLoadRequestEncoder.pack(i)

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
        return Arena.ofConfined().use {
            val request = BpfProgLoadRequestEncoder.encode(it, program, mapFds, programType, programName)
            when (val result = bpfResult(BPF_PROG_LOAD, request.attr, BpfProgLoadLayout.SIZE)) {
                is SyscallResult.Success -> result.value.toInt()
                is SyscallResult.Error -> {
                    val log = request.verifierLog.getString(0).trim()
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
        if (fd < 0) return
        when (val result = native.syscall(SYS_CLOSE, NativeArg.LongArg(fd.toLong()))) {
            is SyscallResult.Success -> Unit
            is SyscallResult.Error -> result.throwErrno("close(Tier E resource fd=$fd)")
        }
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
