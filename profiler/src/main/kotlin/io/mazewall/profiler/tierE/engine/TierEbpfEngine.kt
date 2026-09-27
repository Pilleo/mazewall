package io.mazewall.profiler.tierE.engine

import io.mazewall.ffi.memory.ConfinedSegment
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.fd.recordDescriptorAcquisition
import io.mazewall.platform.native.LinuxNative.SyscallResult
import io.mazewall.platform.native.RawSyscallOperations
import io.mazewall.platform.native.asBpf
import io.mazewall.platform.native.bpf
import io.mazewall.platform.native.getFdOrThrow
import io.mazewall.platform.native.openDescriptor
import io.mazewall.platform.syscall.NativeArg
import io.mazewall.profiler.internal.attribution.CoverageRecoveryMode
import io.mazewall.profiler.internal.attribution.MarkerVersion
import io.mazewall.profiler.internal.attribution.TierEEmissionMode
import io.mazewall.profiler.internal.attribution.TierEKernelCoverageMode
import io.mazewall.profiler.internal.attribution.TierEOptions
import io.mazewall.profiler.internal.attribution.TierERingDrainPhaseMetrics
import io.mazewall.profiler.internal.attribution.TierERingEventDrain
import io.mazewall.profiler.internal.attribution.TierESysEnterAttachmentBackend
import io.mazewall.profiler.internal.attribution.TierESysEnterAttachmentShape
import io.mazewall.profiler.tierE.daemon.UsdtProbeNote
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
 * Loads a tracepoint/raw_syscalls/sys_enter program plus HASH/RINGBUF/ARRAY maps entirely
 * through bpf(2) syscalls via FFM downcalls. No libbpf, no C shim,
 * no marker library.
 */
internal class TierEbpfEngine(
    private val native: RawSyscallOperations,
) : AutoCloseable {
    internal data class RawSysEnterAuditCount(
        val openat: Long,
        val openat2: Long,
        val preSession: Long,
        val noSession: Long,
        val wrongTarget: Long,
    ) {
        internal companion object {
            const val OPENAT_OFFSET: Int = 0
            const val OPENAT2_OFFSET: Int = 8
            const val PRE_SESSION_OFFSET: Int = 16
            const val NO_SESSION_OFFSET: Int = 24
            const val WRONG_TARGET_OFFSET: Int = 32
            const val VALUE_SIZE: Int = 40
        }

        val total: Long get() = openat + openat2
    }

    /** Benchmark-only classification of the exact sys_enter admission and submission boundaries. */
    internal data class ExactAdmissionAuditCount(
        val handlerEntered: Long,
        val sessionMatched: Long,
        val inFlightAdmitted: Long,
        val markerAttached: Long,
        val taskStorageAvailable: Long,
        val activeInvocation: Long,
        val generationValidated: Long,
        val ringReserved: Long,
        val submitted: Long,
        val sessionUnavailable: Long,
        val wrongTarget: Long,
        val inFlightUnavailable: Long,
        val markerDetached: Long,
        val markerStateUnavailable: Long,
        val taskStorageUnavailable: Long,
        val inactiveInvocation: Long,
        val staleMarkerGeneration: Long,
        val ringReservationFailed: Long,
        val low32HandlerEntered: Long,
    ) {
        internal companion object {
            const val HANDLER_ENTERED_OFFSET: Int = 0
            const val SESSION_MATCHED_OFFSET: Int = 8
            const val IN_FLIGHT_ADMITTED_OFFSET: Int = 16
            const val MARKER_ATTACHED_OFFSET: Int = 24
            const val TASK_STORAGE_AVAILABLE_OFFSET: Int = 32
            const val ACTIVE_INVOCATION_OFFSET: Int = 40
            const val GENERATION_VALIDATED_OFFSET: Int = 48
            const val RING_RESERVED_OFFSET: Int = 56
            const val SUBMITTED_OFFSET: Int = 64
            const val SESSION_UNAVAILABLE_OFFSET: Int = 72
            const val WRONG_TARGET_OFFSET: Int = 80
            const val IN_FLIGHT_UNAVAILABLE_OFFSET: Int = 88
            const val MARKER_DETACHED_OFFSET: Int = 96
            const val MARKER_STATE_UNAVAILABLE_OFFSET: Int = 104
            const val TASK_STORAGE_UNAVAILABLE_OFFSET: Int = 112
            const val INACTIVE_INVOCATION_OFFSET: Int = 120
            const val STALE_MARKER_GENERATION_OFFSET: Int = 128
            const val RING_RESERVATION_FAILED_OFFSET: Int = 136
            const val LOW32_HANDLER_ENTERED_OFFSET: Int = 144
            const val VALUE_SIZE: Int = 152
        }

        val admitted: Long get() = generationValidated
        val rejected: Long get() =
            sessionUnavailable +
                wrongTarget +
                inFlightUnavailable +
                markerDetached +
                markerStateUnavailable +
                taskStorageUnavailable +
                inactiveInvocation +
                staleMarkerGeneration +
                ringReservationFailed
    }

    /** Independent, descriptor-free target openat counts from the same quiescent audit window. */
    internal data class EarlyTargetOpenatCounts(
        val main: Long,
        val raw: Long,
    )

    /** Kernel-reported execution evidence; absent kernel fields are never coerced to zero. */
    internal sealed interface BpfProgramExecutionEvidence {
        data class Available(
            val programId: Int,
            val translatedInstructionBytes: Int,
            val runCount: Long?,
            val runTimeNanos: Long?,
        ) : BpfProgramExecutionEvidence

        data class Unavailable(
            val reason: String,
        ) : BpfProgramExecutionEvidence

        data object NotAttached : BpfProgramExecutionEvidence
    }

    private val readinessPoller = TierERingReadinessPoller(native)

    public companion object {
        private val SYS_CLOSE: Long get() = io.mazewall.platform.syscall.Arch
            .current()
            .close
            .toLong()
        private val SYS_IOCTL: Long get() = io.mazewall.platform.syscall.Arch
            .current()
            .ioctl
            .toLong()
        private val SYS_PERF_EVENT_OPEN: Long get() = io.mazewall.platform.syscall.Arch
            .current()
            .perfEventOpen
            .toLong()
        private const val PERF_FLAG_FD_CLOEXEC: Long = 8
        private const val PERF_EVENT_IOC_ENABLE: Long = 0x2400
        private const val PERF_EVENT_IOC_DISABLE: Long = 0x2401
        private const val PERF_EVENT_IOC_SET_BPF: Long = 0x40042408
        private const val PERF_TYPE_TRACEPOINT: Int = 2

        private const val BPF_MAP_CREATE: Long = 0
        private const val BPF_MAP_LOOKUP_ELEM: Long = 1
        private const val BPF_MAP_UPDATE_ELEM: Long = 2
        private const val BPF_MAP_GET_NEXT_KEY: Long = 4
        private const val BPF_PROG_LOAD: Long = 5
        private const val BPF_OBJ_GET_INFO_BY_FD: Long = 15
        private const val BPF_RAW_TRACEPOINT_OPEN: Long = 17
        private const val BPF_BTF_LOAD: Long = 18

        private const val MAP_ARRAY: Int = 2
        private const val MAP_HASH: Int = 1
        private const val MAP_PERCPU_ARRAY: Int = 6
        private const val MAP_LRU_HASH: Int = 9
        private const val MAP_RINGBUF: Int = 27
        private const val MAP_TASK_STORAGE: Int = 29
        private const val PROG_TRACEPOINT: Int = 5
        private const val PROG_KPROBE: Int = 2
        private const val PROG_RAW_TRACEPOINT: Int = 17
        private const val BPF_F_NO_PREALLOC: Int = 1

        // eBPF helper IDs (uapi/linux/bpf.h)
        private const val H_LOOKUP: Int = 1

        public const val EVENT_SIZE: Int = 88
        private const val RING_CAPACITY: Int = 1 shl 20
        private const val COVERAGE_EDGE_CAPACITY: Int = 1 shl 16
        internal const val RECOVERY_EDGE_KEY_SIZE: Int = 24
        internal const val RECOVERY_EDGE_VALUE_SIZE: Int = 96
        private const val CONTEXT_METRIC_CAPACITY: Int = 1 shl 14
        internal const val BENCHMARK_AUDIT_MAX_DESCRIPTORS: Int = 1 shl 17
        private const val RAW_SYS_ENTER_AUDIT_CAPACITY: Int = BENCHMARK_AUDIT_MAX_DESCRIPTORS
        private const val RAW_SYS_ENTER_AUDIT_CPU_CAPACITY: Int = 4_096
        private const val EXACT_ADMISSION_AUDIT_CAPACITY: Int = RAW_SYS_ENTER_AUDIT_CAPACITY
        private const val SESSION_TAG_OFFSET: Long = 0
        private const val TARGET_TGID_OFFSET: Long = 4
        private const val MARKER_ATTACHED_OFFSET: Long = 8
        private const val MARKER_GENERATION_OFFSET: Long = 12
        private const val MARKER_FAILURE_COUNTER_INDEX = 3
        private const val AMBIENT_INACTIVE_COUNTER_INDEX = 4
        private const val EXACT_ADMISSION_COUNTER_INDEX = 5
        private const val MARKER_DETACHED_COUNTER_INDEX = 6
        private const val TASK_STORAGE_UNAVAILABLE_COUNTER_INDEX = 7
        private const val STALE_MARKER_GENERATION_COUNTER_INDEX = 8
        private const val RAW_AUDIT_ARGUMENT_READ_FAILURE_COUNTER_INDEX = 9
        internal const val EXACT_ADMISSION_AUDIT_ARGUMENT_READ_FAILURE_COUNTER_INDEX = 10
        internal const val EXACT_ADMISSION_AUDIT_LOOKUP_FAILURE_COUNTER_INDEX = 11
        private const val COUNTER_MAP_ENTRIES = 13
        private const val IN_FLIGHT_MAP_ENTRIES = 1
        private const val EARLY_TARGET_OPENAT_COUNTER_KEYS = 3
        private const val EARLY_TARGET_OPENAT_ARM_KEY = 2
        private const val BPF_ATTRIBUTE_FD_OFFSET: Long = 0
        private const val BPF_ATTRIBUTE_KEY_OFFSET: Long = 8
        private const val BPF_ATTRIBUTE_VALUE_OFFSET: Long = 16

        internal fun selectTraceAttachmentCpus(
            onlineCpuList: String,
            allowedCpuList: String,
        ): List<Int> {
            val online = parseCpuList(onlineCpuList).toSet()
            return parseCpuList(allowedCpuList).filter(online::contains).also { cpus ->
                check(cpus.isNotEmpty()) { "collector has no online CPUs available for Tier E tracepoint attachment" }
            }
        }

        internal fun selectSysEnterAttachmentBackend(
            configured: TierESysEnterAttachmentBackend,
            architecture: String = System.getProperty("os.arch"),
        ): TierESysEnterAttachmentBackend =
            when (configured) {
            TierESysEnterAttachmentBackend.AUTO -> when (architecture.lowercase()) {
                "amd64", "x86_64" -> TierESysEnterAttachmentBackend.DIRECT_RAW_TRACEPOINT
                else -> TierESysEnterAttachmentBackend.PERF_TRACEPOINT_COMPAT
            }
            TierESysEnterAttachmentBackend.DIRECT_RAW_TRACEPOINT,
            TierESysEnterAttachmentBackend.PERF_TRACEPOINT_COMPAT,
            -> configured
        }

        private fun parseCpuList(cpuList: String): List<Int> =
            cpuList
                .split(',')
                .flatMap { range ->
                    val endpoints = range.split('-', limit = 2).map(String::toInt)
                    if (endpoints.size == 1) listOf(endpoints[0]) else (endpoints[0]..endpoints[1]).toList()
                }
    }

    private val ringCapacity: Int = System.getProperty("mazewall.tier-e.ring-capacity-bytes")?.let { configured ->
        configured.toIntOrNull()?.also {
            require(it >= RING_CAPACITY && it and (it - 1) == 0) {
                "mazewall.tier-e.ring-capacity-bytes must be a power of two at least $RING_CAPACITY"
            }
        } ?: error("mazewall.tier-e.ring-capacity-bytes must be an integer")
    } ?: RING_CAPACITY

    internal fun configuredRingCapacityBytes(): Int = ringCapacity

    /** One eBPF instruction. */
    internal data class Insn(
        val code: Int,
        val dst: Int = 0,
        val src: Int = 0,
        val off: Short = 0,
        val imm: Int = 0,
    )

    private enum class Lifecycle { DETACHED, INSTALLING, ATTACHED, CLOSING, CLOSED }

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
        require(targetTgid > 0)
        require(sessionTag > 0) { "session tag must be positive" }
        val installed = synchronized(lifecycleLock) {
            check(lifecycle == Lifecycle.DETACHED || lifecycle == Lifecycle.CLOSED) { "already installed or closing" }
            lifecycle = Lifecycle.INSTALLING
            Resources()
        }
        try {
            this.options = options
            val sysEnterAttachmentBackend = selectSysEnterAttachmentBackend(options.sysEnterAttachmentBackend)
            installed.sysEnterAttachmentBackend = sysEnterAttachmentBackend
            installed.invocationBtfFd = loadBtf(InvocationStateBtf.load())
            val taskStorageV2 = options.markerVersion == MarkerVersion.V2
            installed.invocationStorageFd = createMap(
            type = MAP_TASK_STORAGE,
            keySize = 4,
            valueSize = if (taskStorageV2) 32 else 24,
            maxEntries = 0,
            mapFlags = BPF_F_NO_PREALLOC,
            btfFd = installed.requireInvocationBtfFd().value,
            btfKeyTypeId = InvocationStateBtf.taskStorageKeyTypeId(),
            btfValueTypeId = if (taskStorageV2) InvocationStateBtf.invocationStateV2TypeId() else InvocationStateBtf.invocationStateTypeId(),
        )
            installed.ringFd = createMap(MAP_RINGBUF, keySize = 0, valueSize = 0, maxEntries = ringCapacity)
            if (
                options.emissionMode == TierEEmissionMode.UNIQUE_STACK_SYSCALL &&
                    options.kernelCoverageMode != TierEKernelCoverageMode.NONE
            ) {
                installed.coverageEdgeFd = createMap(MAP_LRU_HASH, keySize = 16, valueSize = 8, maxEntries = COVERAGE_EDGE_CAPACITY)
            }
            if (options.coverageRecoveryMode != CoverageRecoveryMode.DISABLED) {
                check(options.markerVersion == MarkerVersion.V2) {
                    "coverage recovery requires marker v2"
                }
                installed.recoveryEdgeFd = createMap(
                    MAP_HASH,
                    keySize = RECOVERY_EDGE_KEY_SIZE,
                    valueSize = RECOVERY_EDGE_VALUE_SIZE,
                    maxEntries = options.coverageRecoveryMapCapacity,
                )
            }
            installed.lossFd = createMap(
                MAP_ARRAY,
                keySize = 4,
                valueSize = 8,
                maxEntries = if (options.coverageRecoveryMode == CoverageRecoveryMode.DISABLED) COUNTER_MAP_ENTRIES - 1 else COUNTER_MAP_ENTRIES,
            )
            installed.contextMetricFd = createMap(MAP_LRU_HASH, keySize = 8, valueSize = 8, maxEntries = CONTEXT_METRIC_CAPACITY)
            installed.inFlightFd = createMap(MAP_PERCPU_ARRAY, keySize = 4, valueSize = 8, maxEntries = IN_FLIGHT_MAP_ENTRIES)
            installed.sessionFd = createMap(MAP_ARRAY, keySize = 4, valueSize = 16, maxEntries = 1)
            if (options.benchmarkRawSysEnterAudit) {
                check(possibleCpus().all { it in 0 until RAW_SYS_ENTER_AUDIT_CPU_CAPACITY }) {
                    "raw sys_enter audit CPU exceeds bounded capacity $RAW_SYS_ENTER_AUDIT_CPU_CAPACITY"
                }
                installed.rawSysEnterAuditFd = createMap(
                    MAP_HASH,
                    keySize = 4,
                    valueSize = RawSysEnterAuditCount.VALUE_SIZE,
                    maxEntries = RAW_SYS_ENTER_AUDIT_CAPACITY,
                )
                installed.rawSysEnterAuditCpuFd = createMap(
                    MAP_ARRAY,
                    keySize = 4,
                    valueSize = Long.SIZE_BYTES,
                    maxEntries = RAW_SYS_ENTER_AUDIT_CPU_CAPACITY,
                )
            }
            if (options.benchmarkExactAdmissionAudit) {
                installed.exactAdmissionAuditFd = createMap(
                    MAP_HASH,
                    keySize = 4,
                    valueSize = ExactAdmissionAuditCount.VALUE_SIZE,
                    maxEntries = EXACT_ADMISSION_AUDIT_CAPACITY,
                )
            }
            if (options.usesPairedEarlyTargetAudit()) {
                installed.earlyTargetOpenatFd = createMap(
                    MAP_ARRAY,
                keySize = 4,
                valueSize = Long.SIZE_BYTES,
                maxEntries = EARLY_TARGET_OPENAT_COUNTER_KEYS,
                )
            }
            Arena.ofConfined().use { arena ->
            val configuration = arena.allocate(16, 4)
            configuration.set(ValueLayout.JAVA_INT, SESSION_TAG_OFFSET, sessionTag)
            configuration.set(ValueLayout.JAVA_INT, TARGET_TGID_OFFSET, targetTgid)
            configuration.set(ValueLayout.JAVA_INT, MARKER_ATTACHED_OFFSET, 0)
            configuration.set(ValueLayout.JAVA_INT, MARKER_GENERATION_OFFSET, 0)
                updateElem(arena, installed.requireSessionFd().value, intSeg(arena, 0), configuration)
            }
            installed.markerProgFd =
                loadProg(
                buildMarkerProgram(options),
                listOf(installed.requireInvocationStorageFd().value, installed.requireLossFd().value, installed.requireSessionFd().value),
                PROG_KPROBE,
                "tier_e_marker",
            )
            installed.execProgFd =
                loadProg(
                    buildProcessExecProgram(options),
                    listOf(installed.requireInvocationStorageFd().value, -1, installed.requireSessionFd().value),
                    PROG_RAW_TRACEPOINT,
                    "tier_e_exec",
                )
            if (!options.benchmarkSysEnterAttachmentShape.isAuditOnly()) {
                installed.progFd = loadProg(
                    if (sysEnterAttachmentBackend == TierESysEnterAttachmentBackend.DIRECT_RAW_TRACEPOINT) {
                        buildRawTracepointProgram()
                    } else {
                        buildProgram()
                    },
                    listOf(
                        installed.requireInvocationStorageFd().value,
                        installed.requireRingFd().value,
                        installed.requireLossFd().value,
                        installed.requireSessionFd().value,
                        installed.coverageEdgeFd?.value ?: -1,
                        installed.requireContextMetricFd().value,
                        installed.requireInFlightFd().value,
                        installed.exactAdmissionAuditFd?.value ?: -1,
                        installed.recoveryEdgeFd?.value ?: -1,
                        installed.earlyTargetOpenatFd?.value ?: -1,
                    ),
                    if (sysEnterAttachmentBackend == TierESysEnterAttachmentBackend.DIRECT_RAW_TRACEPOINT) {
                        PROG_RAW_TRACEPOINT
                    } else {
                        PROG_TRACEPOINT
                    },
                    "tier_e_syscall",
                )
                installed.mainSysEnterProgramAttached = true
            }
            if (options.benchmarkRawSysEnterAudit) {
                installed.rawSysEnterAuditProgFd = loadProg(
                    if (sysEnterAttachmentBackend == TierESysEnterAttachmentBackend.DIRECT_RAW_TRACEPOINT) {
                        if (options.benchmarkSysEnterAttachmentShape.usesMinimalAuditProgram()) {
                            TierEBpfProgramBuilder.buildMinimalRawTracepointSysEnterAuditProgram()
                        } else {
                            buildRawTracepointSysEnterAuditProgram()
                        }
                    } else {
                        if (options.benchmarkSysEnterAttachmentShape.usesMinimalAuditProgram()) {
                            TierEBpfProgramBuilder.buildMinimalSysEnterAuditProgram()
                        } else {
                            buildRawSysEnterAuditProgram()
                        }
                    },
                    listOf(
                        installed.requireRawSysEnterAuditFd().value,
                        installed.requireSessionFd().value,
                        installed.requireLossFd().value,
                        installed.requireRawSysEnterAuditCpuFd().value,
                        installed.earlyTargetOpenatFd?.value ?: -1,
                    ),
                    if (sysEnterAttachmentBackend == TierESysEnterAttachmentBackend.DIRECT_RAW_TRACEPOINT) {
                        PROG_RAW_TRACEPOINT
                    } else {
                        PROG_TRACEPOINT
                    },
                    "tier_e_audit",
                )
            }
            if (installed.mainSysEnterProgramAttached) attachSysEnter(installed, sysEnterAttachmentBackend)
            if (options.benchmarkRawSysEnterAudit) attachRawSysEnterAudit(installed, sysEnterAttachmentBackend)
            attachProcessExec(installed)
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

    /** Target-process syscalls observed while marker admission was active but no invocation was active. */
    public fun readAmbientInactiveCount(): Long = readCounter(AMBIENT_INACTIVE_COUNTER_INDEX)

    /** Selected target syscalls that passed marker/task-storage admission before ring reservation. */
    public fun readExactAdmissionCount(): Long = readCounter(EXACT_ADMISSION_COUNTER_INDEX)

    internal fun readExactAdmissionAuditArgumentReadFailureCount(): Long = readCounter(EXACT_ADMISSION_AUDIT_ARGUMENT_READ_FAILURE_COUNTER_INDEX)

    internal fun readExactAdmissionAuditLookupFailureCount(): Long = readCounter(EXACT_ADMISSION_AUDIT_LOOKUP_FAILURE_COUNTER_INDEX)

    /** Target syscalls rejected because the session marker was detached. */
    public fun readMarkerDetachedCount(): Long = readCounter(MARKER_DETACHED_COUNTER_INDEX)

    /** Target syscalls rejected because no task-storage state was available. */
    public fun readTaskStorageUnavailableCount(): Long = readCounter(TASK_STORAGE_UNAVAILABLE_COUNTER_INDEX)

    /** Target syscalls rejected because marker state belongs to an older attachment generation. */
    public fun readStaleMarkerGenerationCount(): Long = readCounter(STALE_MARKER_GENERATION_COUNTER_INDEX)

    /** Retained-edge insertions that failed for reasons other than normal repetition. */
    public fun readRecoveryInsertionFailureCount(): Long = readCounter(TierEBpfProgramBuilder.RECOVERY_INSERTION_FAILURE_COUNTER_INDEX)

    /** Benchmark-only failures while the independent raw-sys_enter audit reads pt_regs arguments. */
    internal fun readRawAuditArgumentReadFailureCount(): Long = readCounter(RAW_AUDIT_ARGUMENT_READ_FAILURE_COUNTER_INDEX)

    /** Configures the benchmark-only audit map before the gated workload is released. */
    internal fun configureRawSysEnterAudit(expectedDirectoryFds: Set<Int>) {
        check(options.benchmarkRawSysEnterAudit) { "raw sys_enter audit is not enabled" }
        require(expectedDirectoryFds.size <= RAW_SYS_ENTER_AUDIT_CAPACITY) { "raw sys_enter audit descriptor count exceeds capacity" }
        require(expectedDirectoryFds.all { it >= 0 }) { "raw sys_enter audit descriptors must be non-negative" }
        val installed = requireInstalled()
        check(!installed.rawSysEnterAuditConfigured) { "raw sys_enter audit was already configured" }
        Arena.ofConfined().use { arena ->
            expectedDirectoryFds.forEach { descriptor ->
                val value = arena.allocate(RawSysEnterAuditCount.VALUE_SIZE.toLong(), 8)
                repeat(RawSysEnterAuditCount.VALUE_SIZE / Long.SIZE_BYTES) { index ->
                    value.set(ValueLayout.JAVA_LONG, index.toLong() * Long.SIZE_BYTES, 0)
                }
                updateElem(arena, installed.requireRawSysEnterAuditFd().value, intSeg(arena, descriptor), value)
            }
        }
        installed.rawSysEnterAuditConfigured = true
    }

    /** Configures the benchmark-only post-marker exact-admission denominator. */
    internal fun configureExactAdmissionAudit(expectedDirectoryFds: Set<Int>) {
        check(options.benchmarkExactAdmissionAudit) { "exact-admission audit is not enabled" }
        require(expectedDirectoryFds.size <= EXACT_ADMISSION_AUDIT_CAPACITY) { "exact-admission audit descriptor count exceeds capacity" }
        require(expectedDirectoryFds.all { it >= 0 }) { "exact-admission audit descriptors must be non-negative" }
        val installed = requireInstalled()
        check(!installed.exactAdmissionAuditConfigured) { "exact-admission audit was already configured" }
        Arena.ofConfined().use { arena ->
            expectedDirectoryFds.forEach { descriptor ->
                val value = arena.allocate(ExactAdmissionAuditCount.VALUE_SIZE.toLong(), 8)
                repeat(ExactAdmissionAuditCount.VALUE_SIZE / Long.SIZE_BYTES) { index ->
                    value.set(ValueLayout.JAVA_LONG, index.toLong() * Long.SIZE_BYTES, 0)
                }
                updateElem(arena, installed.requireExactAdmissionAuditFd().value, intSeg(arena, descriptor), value)
            }
        }
        installed.exactAdmissionAuditConfigured = true
    }

    /** Opens the same target observation window in both programs after attachments and maps are ready. */
    internal fun armEarlyTargetOpenatComparison() {
        check(options.usesPairedEarlyTargetAudit()) { "paired early target audit is not enabled" }
        val installed = requireInstalled()
        check(installed.rawSysEnterAuditConfigured && installed.exactAdmissionAuditConfigured) { "paired audit maps are not configured" }
        check(!installed.earlyTargetOpenatArmed) { "paired early target audit was already armed" }
        Arena.ofConfined().use { arena ->
            val armed = arena.allocate(ValueLayout.JAVA_LONG)
            armed.set(ValueLayout.JAVA_LONG, 0, 1L)
            updateElem(arena, installed.requireEarlyTargetOpenatFd().value, intSeg(arena, EARLY_TARGET_OPENAT_ARM_KEY), armed)
        }
        installed.earlyTargetOpenatArmed = true
    }

    /** Reads only the declared descriptors; absent entries are a configuration failure. */
    internal fun readRawSysEnterAuditCounts(expectedDirectoryFds: Set<Int>): Map<Int, RawSysEnterAuditCount> {
        check(options.benchmarkRawSysEnterAudit) { "raw sys_enter audit is not enabled" }
        val installed = requireInstalled()
        check(installed.rawSysEnterAuditConfigured) { "raw sys_enter audit was not configured" }
        return TierEBenchmarkAuditMapReader.readRaw(native, installed.requireRawSysEnterAuditFd().value, expectedDirectoryFds)
    }

    /** Benchmark-only CPU-local raw-audit counts for declared workload descriptors. */
    internal fun readRawSysEnterAuditCpuCounts(): Map<Int, Long> {
        check(options.benchmarkRawSysEnterAudit) { "raw sys_enter audit is not enabled" }
        val installed = requireInstalled()
        check(installed.rawSysEnterAuditConfigured) { "raw sys_enter audit was not configured" }
        return TierEBenchmarkAuditMapReader.readRawCpu(native, installed.requireRawSysEnterAuditCpuFd().value, possibleCpus())
    }

    /** Captures terminal kernel execution evidence for both attachment programs. */
    internal fun readSysEnterProgramExecutionEvidence(): Map<String, BpfProgramExecutionEvidence> {
        val installed = requireInstalled()
        installed.syscallPerfFds.forEach { rawIoctl(it.value, PERF_EVENT_IOC_DISABLE, 0) }
        installed.rawSysEnterAuditPerfFds.forEach { rawIoctl(it.value, PERF_EVENT_IOC_DISABLE, 0) }
        return mapOf(
            "main" to programExecutionEvidence(installed.progFd),
            "audit" to programExecutionEvidence(installed.rawSysEnterAuditProgFd),
        )
    }

    /** Reads post-marker admission and rejection reasons for each declared descriptor. */
    internal fun readExactAdmissionAuditCounts(expectedDirectoryFds: Set<Int>): Map<Int, ExactAdmissionAuditCount> {
        check(options.benchmarkExactAdmissionAudit) { "exact-admission audit is not enabled" }
        val installed = requireInstalled()
        check(installed.exactAdmissionAuditConfigured) { "exact-admission audit was not configured" }
        return TierEBenchmarkAuditMapReader.readExact(native, installed.requireExactAdmissionAuditFd().value, expectedDirectoryFds)
    }

    /** Compare the same session window after the target exited; ownership stays with this engine. */
    internal fun readEarlyTargetOpenatCounts(): EarlyTargetOpenatCounts {
        check(options.usesPairedEarlyTargetAudit()) { "paired early target audit is not enabled" }
        val installed = requireInstalled()
        check(installed.earlyTargetOpenatArmed) { "paired early target audit was not armed" }
        return TierEBenchmarkAuditMapReader.readEarlyTargetOpenat(native, installed.requireEarlyTargetOpenatFd().value)
    }

    /** Waits until all handlers admitted before marker detachment reach their completion epilogue. */
    public fun awaitSysEnterQuiescence(timeoutNanos: Long): Boolean {
        require(timeoutNanos >= 0) { "timeoutNanos must not be negative" }
        val deadline = System.nanoTime() + timeoutNanos
        try {
            do {
                if (readInFlightCount() == 0L) return true
                Thread.sleep(1)
            } while (System.nanoTime() < deadline)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        return readInFlightCount() == 0L
    }

    /** Snapshot of bounded per-invocation event counts from the BPF metric map. */
    public fun readContextMetrics(): Map<Long, Long> =
        Arena.ofConfined().use { arena ->
            val installed = requireInstalled()
            val key = arena.allocate(8, 8)
            val nextKey = arena.allocate(8, 8)
            val cpuCount = possibleCpus().size
            val value = arena.allocate(Long.SIZE_BYTES.toLong() * cpuCount, Long.SIZE_BYTES.toLong())
            val metrics = linkedMapOf<Long, Long>()
            var currentKey: MemorySegment? = null
            while (true) {
                val nextAttr = arena.allocate(32)
                nextAttr.set(ValueLayout.JAVA_INT, BPF_ATTRIBUTE_FD_OFFSET, installed.requireContextMetricFd().value)
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
                lookupAttr.set(ValueLayout.JAVA_INT, BPF_ATTRIBUTE_FD_OFFSET, installed.requireContextMetricFd().value)
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
            attr.set(ValueLayout.JAVA_INT, 0, installed.requireLossFd().value)
            attr.set(ValueLayout.ADDRESS, 8, key)
            attr.set(ValueLayout.ADDRESS, 16, value)
            bpfCall(H_LOOKUP.toLong(), attr, 24)
            value.get(ValueLayout.JAVA_LONG, 0)
        }

    /**
     * Reads one bounded page of the session-bound retained-edge map.
     * Root-only: requires an installed recovery map. Entries are immutable
     * first-observation representatives; an existing key is never rewritten.
     */
    public fun scanRecoveryBatch(
        cursor: ByteArray?,
        maxEntries: Int,
    ): io.mazewall.profiler.internal.attribution.RecoveryScanPage {
        require(maxEntries in 1..io.mazewall.profiler.internal.attribution.CoverageRecoveryMode.MAX_SCAN_BATCH) {
            "scan batch must be bounded"
        }
        if (cursor != null) require(cursor.size == RECOVERY_EDGE_KEY_SIZE) { "recovery cursor must be 24 bytes" }
        return Arena.ofConfined().use { arena ->
            val installed = requireInstalled()
            val mapFd = checkNotNull(installed.recoveryEdgeFd?.value) { "recovery map is not installed" }
            val entries = ArrayList<io.mazewall.profiler.internal.attribution.RecoveryMapEntry>(maxEntries)
            var currentKey: MemorySegment? = cursor?.let { bytes ->
                val seg = arena.allocate(RECOVERY_EDGE_KEY_SIZE.toLong(), Int.SIZE_BYTES.toLong())
                MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0, RECOVERY_EDGE_KEY_SIZE)
                seg
            }
            val nextKey = arena.allocate(RECOVERY_EDGE_KEY_SIZE.toLong(), Int.SIZE_BYTES.toLong())
            val value = arena.allocate(RECOVERY_EDGE_VALUE_SIZE.toLong(), 8)
            while (entries.size < maxEntries) {
                val nextAttr = arena.allocate(32)
                nextAttr.set(ValueLayout.JAVA_INT, BPF_ATTRIBUTE_FD_OFFSET, mapFd)
                nextAttr.set(ValueLayout.ADDRESS, BPF_ATTRIBUTE_KEY_OFFSET, currentKey ?: MemorySegment.NULL)
                nextAttr.set(ValueLayout.ADDRESS, BPF_ATTRIBUTE_VALUE_OFFSET, nextKey)
                when (val result = bpfResult(BPF_MAP_GET_NEXT_KEY, nextAttr, 32)) {
                    is SyscallResult.Error -> {
                        if (result.errno == io.mazewall.ffi.NativeConstants.ENOENT) break
                        result.throwErrno("bpf(BPF_MAP_GET_NEXT_KEY)")
                    }
                    is SyscallResult.Success -> Unit
                }
                val keyBytes = ByteArray(RECOVERY_EDGE_KEY_SIZE)
                MemorySegment.copy(nextKey, ValueLayout.JAVA_BYTE, 0, keyBytes, 0, RECOVERY_EDGE_KEY_SIZE)
                val lookupAttr = arena.allocate(32)
                lookupAttr.set(ValueLayout.JAVA_INT, BPF_ATTRIBUTE_FD_OFFSET, mapFd)
                lookupAttr.set(ValueLayout.ADDRESS, BPF_ATTRIBUTE_KEY_OFFSET, nextKey)
                lookupAttr.set(ValueLayout.ADDRESS, BPF_ATTRIBUTE_VALUE_OFFSET, value)
                when (val result = bpfResult(BPF_MAP_LOOKUP_ELEM, lookupAttr, 32)) {
                    is SyscallResult.Success -> {
                        val valueBytes = ByteArray(RECOVERY_EDGE_VALUE_SIZE)
                        MemorySegment.copy(value, ValueLayout.JAVA_BYTE, 0, valueBytes, 0, RECOVERY_EDGE_VALUE_SIZE)
                        entries.add(
                            io.mazewall.profiler.internal.attribution
                            .RecoveryMapEntry(keyBytes, valueBytes),
                        )
                    }
                    is SyscallResult.Error -> {
                        // Entries in this map are never deleted. A next-key
                        // lookup followed by ENOENT is an incomplete scan,
                        // including during live collection: do not skip it.
                        result.throwErrno("bpf(BPF_MAP_LOOKUP_ELEM recovery)")
                    }
                }
                currentKey = arena.allocate(RECOVERY_EDGE_KEY_SIZE.toLong(), Int.SIZE_BYTES.toLong()).also { seg ->
                    MemorySegment.copy(nextKey, ValueLayout.JAVA_BYTE, 0, seg, ValueLayout.JAVA_BYTE, 0, RECOVERY_EDGE_KEY_SIZE.toLong())
                }
            }
            val done = entries.size < maxEntries
            io.mazewall.profiler.internal.attribution.RecoveryScanPage(
                entries = entries,
                nextCursor = if (done || entries.isEmpty()) null else entries.last().key,
                complete = done,
            )
        }
    }

    private fun readInFlightCount(): Long =
        Arena.ofConfined().use { arena ->
            val installed = requireInstalled()
            val cpuCount = possibleCpus().size
            val value = arena.allocate(Long.SIZE_BYTES.toLong() * cpuCount, Long.SIZE_BYTES.toLong())
            val attr = arena.allocate(24)
            attr.set(ValueLayout.JAVA_INT, 0, installed.requireInFlightFd().value)
            attr.set(ValueLayout.ADDRESS, 8, intSeg(arena, 0))
            attr.set(ValueLayout.ADDRESS, 16, value)
            bpfCall(H_LOOKUP.toLong(), attr, 24)
            (0 until cpuCount).sumOf { index -> value.get(ValueLayout.JAVA_LONG, index.toLong() * Long.SIZE_BYTES) }
        }

    public fun attachMarker(
        targetPid: Int,
        agentLibrary: Path,
        markerOffset: Long,
    ) = attachMarkerAtOffset(targetPid, agentLibrary, markerOffset)

    /** Attaches the existing marker program at one validated USDT probe site. */
    internal fun attachUsdtMarker(
        targetPid: Int,
        agentLibrary: Path,
        note: UsdtProbeNote,
    ) {
        attachMarkerAtOffset(targetPid, agentLibrary, note.fileOffset)
    }

    private fun attachMarkerAtOffset(
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
            // perf_event_open binds this trace-uprobe event to one CPU. The target JVM may
            // run carrier threads on every CPU allowed to this collector, so each CPU needs
            // one attachment. A tracepoint hit belongs to its executing CPU and is not replayed
            // through sibling CPU descriptors.
            for (cpu in traceAttachmentCpus()) {
                val fd = native
                    .syscall(
                    SYS_PERF_EVENT_OPEN,
                    NativeArg.MemoryArg(ConfinedSegment(attr)),
                    NativeArg.LongArg(-1),
                    NativeArg.LongArg(cpu.toLong()),
                    NativeArg.LongArg(-1),
                    NativeArg.LongArg(PERF_FLAG_FD_CLOEXEC),
                ).recordDescriptorAcquisition()
                    .getFdOrThrow("perf_event_open(uprobe,cpu=$cpu)", FileDescriptorRole.Bpf)
                installed.markerPerfFds += fd
                rawIoctl(fd.value, PERF_EVENT_IOC_SET_BPF, installed.requireMarkerProgFd().value.toLong(), "marker uprobe cpu=$cpu")
                rawIoctl(fd.value, PERF_EVENT_IOC_ENABLE, 0)
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
        markerPerfFds.forEach { closeOwned(it) }
    }

    /** Drains committed syscall records without waiting or timestamp correlation. */
    public fun drainEvents(): List<SyscallInvocationEvent> {
        val installed = requireInstalled()
        val reader = installed.ringReader ?: BpfRingBufferReader(native, installed.requireRingFd().value, ringCapacity.toLong(), EVENT_SIZE).also {
            installed.ringReader = it
        }
        return when (options.ringDecodeMode) {
            io.mazewall.profiler.internal.attribution.TierERingDecodeMode.DIRECT ->
                reader.drainDecoded { event ->
                    SyscallInvocationEvent.fromSegment(
                        event,
                        options.ringArgumentListMode == io.mazewall.profiler.internal.attribution.TierERingArgumentListMode.LAMBDA_COPY,
                        options.ringArgumentViewMode == io.mazewall.profiler.internal.attribution.TierERingArgumentViewMode.EAGER_CONTROL,
                    )
                }
            io.mazewall.profiler.internal.attribution.TierERingDecodeMode.LEGACY_BYTES ->
                reader.drain().map { event ->
                    SyscallInvocationEvent.fromBytes(
                        event,
                        options.ringArgumentViewMode == io.mazewall.profiler.internal.attribution.TierERingArgumentViewMode.EAGER_CONTROL,
                    )
                }
        }
    }

    /** Waits for a ring-buffer notification without consuming or interpreting a record. */
    internal fun awaitEvents(timeoutMillis: Int): io.mazewall.profiler.internal.attribution.TierEEventReadiness = readinessPoller.await(requireInstalled().requireRingFd().value, timeoutMillis)

    /** Backend and CPU attachment scope retained for privileged benchmark evidence. */
    internal fun sysEnterAttachmentEvidence(): Pair<TierESysEnterAttachmentBackend, List<Int>> {
        val installed = requireInstalled()
        return requireNotNull(installed.sysEnterAttachmentBackend) { "sys_enter attachment backend is unavailable" } to installed.sysEnterAttachmentCpus.toList()
    }

    /** Static program shape retained for benchmark-only kernel-work comparisons. */
    internal fun sysEnterProgramEvidence(): TierESysEnterProgramEvidence {
        val program =
            if (options.sysEnterAttachmentBackend == TierESysEnterAttachmentBackend.DIRECT_RAW_TRACEPOINT) buildRawTracepointProgram() else buildProgram()
        return TierESysEnterProgramEvidence(
            kernelCoverageMode = if (options.emissionMode == TierEEmissionMode.UNIQUE_STACK_SYSCALL) options.kernelCoverageMode else TierEKernelCoverageMode.NONE,
            instructionCount = program.size,
            lookupHelperCalls = program.count { it.code == 0x85 && it.imm == 1 },
            updateHelperCalls = program.count { it.code == 0x85 && it.imm == 2 },
        )
    }

    internal data class TierESysEnterProgramEvidence(
        val kernelCoverageMode: TierEKernelCoverageMode,
        val instructionCount: Int,
        val lookupHelperCalls: Int,
        val updateHelperCalls: Int,
    )

    /**
     * Benchmark-only whole-batch timing. The legacy path records its owned-byte copy and Kotlin
     * decoding phases separately, without putting a clock read into the record loop. Production
     * draining remains clock-free and retains the same ownership boundary.
     */
    internal fun drainEventsMeasured(): TierERingEventDrain {
        if (options.ringDecodeMode != io.mazewall.profiler.internal.attribution.TierERingDecodeMode.LEGACY_BYTES) {
            return TierERingEventDrain(drainEvents())
        }
        val installed = requireInstalled()
        val reader = installed.ringReader ?: BpfRingBufferReader(native, installed.requireRingFd().value, ringCapacity.toLong(), EVENT_SIZE).also {
            installed.ringReader = it
        }
        val copyStarted = System.nanoTime()
        val bytes = reader.drain()
        val copyNanos = System.nanoTime() - copyStarted
        val decodeStarted = System.nanoTime()
        val events = bytes.map { event ->
            SyscallInvocationEvent.fromBytes(
                event,
                options.ringArgumentViewMode == io.mazewall.profiler.internal.attribution.TierERingArgumentViewMode.EAGER_CONTROL,
            )
        }
        val decodeNanos = System.nanoTime() - decodeStarted
        return TierERingEventDrain(
            events,
            TierERingDrainPhaseMetrics(
                payloadSliceNanos = copyNanos,
                decodeNanos = decodeNanos,
                readyRecords = events.size.toLong(),
            ),
        )
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
            lookup.set(ValueLayout.JAVA_INT, 0, installed.requireSessionFd().value)
            lookup.set(ValueLayout.ADDRESS, 8, key)
            lookup.set(ValueLayout.ADDRESS, 16, current)
            bpfCall(BPF_MAP_LOOKUP_ELEM, lookup, 32)
            value.copyFrom(current)
            check(current.get(ValueLayout.JAVA_INT, MARKER_GENERATION_OFFSET) != -1) {
                "marker attachment generation is exhausted"
            }
            value.set(ValueLayout.JAVA_INT, MARKER_ATTACHED_OFFSET, if (attached) 1 else 0)
            value.set(
                ValueLayout.JAVA_INT,
                MARKER_GENERATION_OFFSET,
                current.get(ValueLayout.JAVA_INT, MARKER_GENERATION_OFFSET) + 1,
            )
            updateElem(arena, installed.requireSessionFd().value, key, value)
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
            fd: BpfFd?,
            clear: () -> Unit,
        ) {
            try {
                closeOwned(fd)
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
        val syscallPerfFds = installed.syscallPerfFds.toList()
        installed.syscallPerfFds.clear()
        syscallPerfFds.asReversed().forEach { close(it) {} }
        val rawAuditPerfFds = installed.rawSysEnterAuditPerfFds.toList()
        installed.rawSysEnterAuditPerfFds.clear()
        rawAuditPerfFds.asReversed().forEach { close(it) {} }
        close(installed.syscallRawTracepointFd) { installed.syscallRawTracepointFd = null }
        close(installed.rawSysEnterAuditRawTracepointFd) { installed.rawSysEnterAuditRawTracepointFd = null }
        close(installed.execRawTracepointFd) { installed.execRawTracepointFd = null }
        close(installed.progFd) { installed.progFd = null }
        close(installed.rawSysEnterAuditProgFd) { installed.rawSysEnterAuditProgFd = null }
        close(installed.markerProgFd) { installed.markerProgFd = null }
        close(installed.execProgFd) { installed.execProgFd = null }
        close(installed.ringFd) { installed.ringFd = null }
        close(installed.lossFd) { installed.lossFd = null }
        close(installed.contextMetricFd) { installed.contextMetricFd = null }
        close(installed.inFlightFd) { installed.inFlightFd = null }
        close(installed.sessionFd) { installed.sessionFd = null }
        close(installed.coverageEdgeFd) { installed.coverageEdgeFd = null }
        close(installed.recoveryEdgeFd) { installed.recoveryEdgeFd = null }
        close(installed.rawSysEnterAuditFd) { installed.rawSysEnterAuditFd = null }
        close(installed.rawSysEnterAuditCpuFd) { installed.rawSysEnterAuditCpuFd = null }
        close(installed.exactAdmissionAuditFd) { installed.exactAdmissionAuditFd = null }
        close(installed.earlyTargetOpenatFd) { installed.earlyTargetOpenatFd = null }
        close(installed.invocationStorageFd) { installed.invocationStorageFd = null }
        close(installed.invocationBtfFd) { installed.invocationBtfFd = null }
        cleanupFailure?.let { throw it }
    }

    // ── program construction ────────────────────────────────────────────────

    internal fun buildProgram(options: TierEOptions = this.options): List<Insn> = TierEBpfProgramBuilder.buildSysEnterProgram(options)

    internal fun buildRawTracepointProgram(options: TierEOptions = this.options): List<Insn> = TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram(options)

    internal fun buildRawSysEnterAuditProgram(): List<Insn> = TierEBpfProgramBuilder.buildRawSysEnterAuditProgram(options.usesPairedEarlyTargetAudit())

    internal fun buildRawTracepointSysEnterAuditProgram(): List<Insn> = TierEBpfProgramBuilder.buildRawTracepointSysEnterAuditProgram(options.usesPairedEarlyTargetAudit())

    /** x86_64 uprobe program for `mazewall_stack_marker(uint64_t)`. */
    internal fun buildMarkerProgram(options: TierEOptions = this.options): List<Insn> = TierEBpfProgramBuilder.buildMarkerProgram(options)

    /** Tracepoint program that invalidates task storage after a successful exec. */
    internal fun buildProcessExecProgram(options: TierEOptions = this.options): List<Insn> = TierEBpfProgramBuilder.buildProcessExecProgram(options)

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
    ) = native.bpf(cmd, ConfinedSegment(attr), size)

    private fun createMap(
        type: Int,
        keySize: Int,
        valueSize: Int,
        maxEntries: Int,
        mapFlags: Int = 0,
        btfFd: Int = 0,
        btfKeyTypeId: Int = 0,
        btfValueTypeId: Int = 0,
    ): BpfFd =
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
            native.asBpf().openDescriptor(BPF_MAP_CREATE, ConfinedSegment(attr), 72, "bpf(BPF_MAP_CREATE)")
        }

    private fun loadBtf(bytes: ByteArray): BpfFd =
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
            is SyscallResult.Success -> result.recordDescriptorAcquisition().getFdOrThrow("BPF_BTF_LOAD", FileDescriptorRole.Bpf)
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
    ): BpfFd {
        return Arena.ofConfined().use {
            val request = BpfProgLoadRequestEncoder.encode(it, program, mapFds, programType, programName)
            when (val result = bpfResult(BPF_PROG_LOAD, request.attr, BpfProgLoadLayout.SIZE)) {
                is SyscallResult.Success -> result.recordDescriptorAcquisition().getFdOrThrow("BPF_PROG_LOAD", FileDescriptorRole.Bpf)
                is SyscallResult.Error -> {
                    val log = request.verifierLog.getString(0).trim()
                    val diagnostic = if (log.isEmpty()) "no verifier log" else log
                    throw IllegalStateException("BPF_PROG_LOAD failed: $result; verifier: $diagnostic")
                }
            }
        }
    }

    private fun attachSysEnter(
        installed: Resources,
        backend: TierESysEnterAttachmentBackend,
    ) {
        if (backend == TierESysEnterAttachmentBackend.DIRECT_RAW_TRACEPOINT) {
            installed.syscallRawTracepointFd = openRawTracepoint("sys_enter", installed.requireProgFd())
            return
        }
        val tracepoint = RawSyscallTracepoint.discover()
        Arena.ofConfined().use {
            // A perf tracepoint descriptor is CPU-local. Attach on every CPU available to
            // this collector so migration cannot create an unobserved raw-syscall window.
            traceAttachmentCpus().forEach { cpu ->
                val attr = it.allocate(PerfEventAttrLayout.SIZE)
                attr.set(ValueLayout.JAVA_INT, PerfEventAttrLayout.TYPE, PERF_TYPE_TRACEPOINT)
                attr.set(ValueLayout.JAVA_INT, PerfEventAttrLayout.SIZE_FIELD, PerfEventAttrLayout.SIZE.toInt())
                attr.set(ValueLayout.JAVA_LONG, PerfEventAttrLayout.CONFIG, tracepoint.id.toLong())
                attr.set(ValueLayout.JAVA_LONG, PerfEventAttrLayout.SAMPLE_PERIOD, 1)
                val fd = native
                    .syscall(
                        SYS_PERF_EVENT_OPEN,
                        NativeArg.MemoryArg(ConfinedSegment(attr)),
                        NativeArg.LongArg(-1),
                        NativeArg.LongArg(cpu.toLong()),
                        NativeArg.LongArg(-1),
                        NativeArg.LongArg(PERF_FLAG_FD_CLOEXEC),
                ).recordDescriptorAcquisition()
                    .getFdOrThrow("perf_event_open(raw_syscalls/sys_enter,cpu=$cpu)", FileDescriptorRole.Bpf)
                installed.syscallPerfFds += fd
                installed.sysEnterAttachmentCpus += cpu
                rawIoctl(fd.value, PERF_EVENT_IOC_SET_BPF, installed.requireProgFd().value.toLong(), "sys_enter perf tracepoint cpu=$cpu")
                rawIoctl(fd.value, PERF_EVENT_IOC_ENABLE, 0)
            }
        }
    }

    private fun attachProcessExec(installed: Resources) {
        // The lifecycle program never reads tracepoint context.  Raw-tracepoint
        // attachment avoids the per-CPU PERF_EVENT_IOC_SET_BPF path, which is
        // rejected with EEXIST on supported privileged-container hosts.
        installed.execRawTracepointFd = openRawTracepoint(
            "sched_process_exec",
            requireNotNull(installed.execProgFd),
        )
    }

    private fun attachRawSysEnterAudit(
        installed: Resources,
        backend: TierESysEnterAttachmentBackend,
    ) {
        if (backend == TierESysEnterAttachmentBackend.DIRECT_RAW_TRACEPOINT) {
            installed.rawSysEnterAuditRawTracepointFd = openRawTracepoint(
                "sys_enter",
                installed.requireRawSysEnterAuditProgFd(),
            )
            return
        }
        val tracepoint = RawSyscallTracepoint.discover()
        Arena.ofConfined().use { arena ->
            traceAttachmentCpus().forEach { cpu ->
                val attr = arena.allocate(PerfEventAttrLayout.SIZE)
                attr.set(ValueLayout.JAVA_INT, PerfEventAttrLayout.TYPE, PERF_TYPE_TRACEPOINT)
                attr.set(ValueLayout.JAVA_INT, PerfEventAttrLayout.SIZE_FIELD, PerfEventAttrLayout.SIZE.toInt())
                attr.set(ValueLayout.JAVA_LONG, PerfEventAttrLayout.CONFIG, tracepoint.id.toLong())
                attr.set(ValueLayout.JAVA_LONG, PerfEventAttrLayout.SAMPLE_PERIOD, 1)
                val fd = native
                    .syscall(
                        SYS_PERF_EVENT_OPEN,
                        NativeArg.MemoryArg(ConfinedSegment(attr)),
                        NativeArg.LongArg(-1),
                        NativeArg.LongArg(cpu.toLong()),
                        NativeArg.LongArg(-1),
                        NativeArg.LongArg(PERF_FLAG_FD_CLOEXEC),
                    ).recordDescriptorAcquisition()
                    .getFdOrThrow("perf_event_open(raw_syscalls/sys_enter audit,cpu=$cpu)", FileDescriptorRole.Bpf)
                installed.rawSysEnterAuditPerfFds += fd
                if (!installed.mainSysEnterProgramAttached) installed.sysEnterAttachmentCpus += cpu
                rawIoctl(fd.value, PERF_EVENT_IOC_SET_BPF, installed.requireRawSysEnterAuditProgFd().value.toLong(), "raw sys_enter audit cpu=$cpu")
                rawIoctl(fd.value, PERF_EVENT_IOC_ENABLE, 0)
            }
        }
    }

    private fun openRawTracepoint(
        name: String,
        program: BpfFd,
    ): BpfFd =
        Arena.ofConfined().use { arena ->
        val attr = arena.allocate(BpfRawTracepointOpenLayout.SIZE, 8)
        attr.set(ValueLayout.ADDRESS, BpfRawTracepointOpenLayout.NAME, arena.allocateFrom(name))
        attr.set(ValueLayout.JAVA_INT, BpfRawTracepointOpenLayout.PROGRAM_FD, program.value)
        when (val result = bpfResult(BPF_RAW_TRACEPOINT_OPEN, attr, BpfRawTracepointOpenLayout.SIZE)) {
            is SyscallResult.Success ->
                result
                .recordDescriptorAcquisition()
                .getFdOrThrow("BPF_RAW_TRACEPOINT_OPEN($name)", FileDescriptorRole.Bpf)
            is SyscallResult.Error -> error("BPF_RAW_TRACEPOINT_OPEN($name) failed: $result")
        }
    }

    private fun programExecutionEvidence(fd: BpfFd?): BpfProgramExecutionEvidence {
        if (fd == null) return BpfProgramExecutionEvidence.NotAttached
        return runCatching {
            Arena.ofConfined().use { arena ->
                val info = arena.allocate(BpfProgramInfoLayout.SIZE, 8)
                val attr = arena.allocate(BpfObjGetInfoLayout.SIZE, 8)
                attr.set(ValueLayout.JAVA_INT, BpfObjGetInfoLayout.BPF_FD, fd.value)
                attr.set(ValueLayout.JAVA_INT, BpfObjGetInfoLayout.INFO_LEN, BpfProgramInfoLayout.SIZE.toInt())
                attr.set(ValueLayout.ADDRESS, BpfObjGetInfoLayout.INFO, info)
                bpfCall(BPF_OBJ_GET_INFO_BY_FD, attr, BpfObjGetInfoLayout.SIZE)
                val available = attr.get(ValueLayout.JAVA_INT, BpfObjGetInfoLayout.INFO_LEN)
                val runCount = if (available >= BpfProgramInfoLayout.RUN_COUNT + Long.SIZE_BYTES) {
                    info.get(ValueLayout.JAVA_LONG, BpfProgramInfoLayout.RUN_COUNT)
                } else {
                    null
                }
                val runTimeNanos = if (available >= BpfProgramInfoLayout.RUN_TIME_NANOS + Long.SIZE_BYTES) {
                    info.get(ValueLayout.JAVA_LONG, BpfProgramInfoLayout.RUN_TIME_NANOS)
                } else {
                    null
                }
                if (runCount == null || runTimeNanos == null || (runCount == 0L && runTimeNanos == 0L)) {
                    BpfProgramExecutionEvidence.Unavailable("kernel BPF runtime statistics are unavailable")
                } else {
                    BpfProgramExecutionEvidence.Available(
                        programId = info.get(ValueLayout.JAVA_INT, BpfProgramInfoLayout.ID),
                        translatedInstructionBytes = info.get(ValueLayout.JAVA_INT, BpfProgramInfoLayout.XLATED_PROGRAM_LENGTH),
                        runCount = runCount,
                        runTimeNanos = runTimeNanos,
                    )
                }
            }
        }.getOrElse { BpfProgramExecutionEvidence.Unavailable(it.message ?: it::class.simpleName.orEmpty()) }
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

    private fun closeOwned(fd: BpfFd?) {
        if (fd == null) return
        if (!fd.claimForClose()) return
        when (val result = native.syscall(SYS_CLOSE, NativeArg.LongArg(fd.value.toLong()))) {
            is SyscallResult.Success -> Unit
            is SyscallResult.Error -> result.throwErrno("close(Tier E resource fd=${fd.value})")
        }
    }

    private fun onlineCpus(): List<Int> = cpusFrom(Path.of("/sys/devices/system/cpu/online"))

    /** CPUs this collector process may legally attach to under its current affinity/cgroup. */
    private fun traceAttachmentCpus(): List<Int> {
        val allowed = Files
            .readAllLines(Path.of("/proc/self/status"))
            .firstOrNull { it.startsWith("Cpus_allowed_list:") }
            ?.substringAfter(':')
            ?.trim()
            ?.let(::cpusFrom)
            ?: error("/proc/self/status has no Cpus_allowed_list")
        return selectTraceAttachmentCpus(Files.readString(Path.of("/sys/devices/system/cpu/online")).trim(), allowed.joinToString(","))
    }

    private fun possibleCpus(): List<Int> = cpusFrom(Path.of("/sys/devices/system/cpu/possible"))

    private fun cpusFrom(path: Path): List<Int> = cpusFrom(Files.readString(path).trim())

    private fun cpusFrom(cpuList: String): List<Int> = parseCpuList(cpuList)

    private fun rawIoctl(
        fd: Int,
        request: Long,
        argument: Long,
        operation: String = "raw ioctl",
    ) {
        native
            .syscall(
            SYS_IOCTL,
            NativeArg.LongArg(fd.toLong()),
            NativeArg.LongArg(request),
            NativeArg.LongArg(argument),
        ).getOrThrow("$operation ioctl(fd=$fd, request=$request)")
    }
}

/** The complete FD ownership set for one installation, unpublished until acquisition succeeds. */
private class Resources {
    var invocationStorageFd: BpfFd? = null
    var invocationBtfFd: BpfFd? = null
    var ringFd: BpfFd? = null
    var lossFd: BpfFd? = null
    var contextMetricFd: BpfFd? = null
    var inFlightFd: BpfFd? = null
    var sessionFd: BpfFd? = null
    var coverageEdgeFd: BpfFd? = null
    var recoveryEdgeFd: BpfFd? = null
    var rawSysEnterAuditFd: BpfFd? = null
    var rawSysEnterAuditCpuFd: BpfFd? = null
    var exactAdmissionAuditFd: BpfFd? = null
    var earlyTargetOpenatFd: BpfFd? = null
    var progFd: BpfFd? = null
    var rawSysEnterAuditProgFd: BpfFd? = null
    var markerProgFd: BpfFd? = null
    var execProgFd: BpfFd? = null
    val syscallPerfFds = mutableListOf<BpfFd>()
    val rawSysEnterAuditPerfFds = mutableListOf<BpfFd>()
    var syscallRawTracepointFd: BpfFd? = null
    var rawSysEnterAuditRawTracepointFd: BpfFd? = null
    var execRawTracepointFd: BpfFd? = null
    val markerPerfFds = mutableListOf<BpfFd>()
    var ringReader: BpfRingBufferReader? = null
    var rawSysEnterAuditConfigured = false
    var exactAdmissionAuditConfigured = false
    var earlyTargetOpenatArmed = false
    var sysEnterAttachmentBackend: TierESysEnterAttachmentBackend? = null
    val sysEnterAttachmentCpus = mutableListOf<Int>()
    var mainSysEnterProgramAttached: Boolean = false

    fun requireInvocationBtfFd(): BpfFd = requireNotNull(invocationBtfFd) { "invocation BTF descriptor was not acquired" }

    fun requireInvocationStorageFd(): BpfFd = requireNotNull(invocationStorageFd) { "invocation storage descriptor was not acquired" }

    fun requireRingFd(): BpfFd = requireNotNull(ringFd) { "ring buffer descriptor was not acquired" }

    fun requireLossFd(): BpfFd = requireNotNull(lossFd) { "loss counter descriptor was not acquired" }

    fun requireContextMetricFd(): BpfFd = requireNotNull(contextMetricFd) { "context metric descriptor was not acquired" }

    fun requireInFlightFd(): BpfFd = requireNotNull(inFlightFd) { "in-flight counter descriptor was not acquired" }

    fun requireSessionFd(): BpfFd = requireNotNull(sessionFd) { "session descriptor was not acquired" }

    fun requireRawSysEnterAuditFd(): BpfFd = requireNotNull(rawSysEnterAuditFd) { "raw sys_enter audit descriptor was not acquired" }

    fun requireRawSysEnterAuditCpuFd(): BpfFd = requireNotNull(rawSysEnterAuditCpuFd) { "raw sys_enter audit CPU descriptor was not acquired" }

    fun requireExactAdmissionAuditFd(): BpfFd = requireNotNull(exactAdmissionAuditFd) { "exact-admission audit descriptor was not acquired" }

    fun requireEarlyTargetOpenatFd(): BpfFd = requireNotNull(earlyTargetOpenatFd) { "early target openat descriptor was not acquired" }

    fun requireProgFd(): BpfFd = requireNotNull(progFd) { "syscall program descriptor was not acquired" }

    fun requireMarkerProgFd(): BpfFd = requireNotNull(markerProgFd) { "marker program descriptor was not acquired" }

    fun requireRawSysEnterAuditProgFd(): BpfFd = requireNotNull(rawSysEnterAuditProgFd) { "raw sys_enter audit program descriptor was not acquired" }
}

private typealias BpfFd = FileDescriptor<FileDescriptorRole.Bpf, FdState.Open, FdOwnership.Owned>

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

/** Stable BPF_OBJ_GET_INFO_BY_FD arm of union bpf_attr on 64-bit Linux. */
internal object BpfObjGetInfoLayout {
    const val SIZE: Long = 16
    const val BPF_FD: Long = 0
    const val INFO_LEN: Long = 4
    const val INFO: Long = 8
}

/** Prefix of bpf_prog_info through execution counters, guarded by returned info_len. */
internal object BpfProgramInfoLayout {
    const val SIZE: Long = 208
    const val ID: Long = 4
    const val XLATED_PROGRAM_LENGTH: Long = 20
    const val RUN_TIME_NANOS: Long = 192
    const val RUN_COUNT: Long = 200
}

private fun TierESysEnterAttachmentShape.isAuditOnly(): Boolean =
    this == TierESysEnterAttachmentShape.DESCRIPTOR_AUDIT_ONLY ||
        this == TierESysEnterAttachmentShape.MINIMAL_AUDIT_ONLY

private fun TierESysEnterAttachmentShape.usesMinimalAuditProgram(): Boolean =
    this == TierESysEnterAttachmentShape.MINIMAL_COUNTER_ONLY ||
        this == TierESysEnterAttachmentShape.MINIMAL_AUDIT_ONLY

private fun TierEOptions.usesPairedEarlyTargetAudit(): Boolean =
    benchmarkRawSysEnterAudit &&
        benchmarkExactAdmissionAudit &&
        benchmarkSysEnterAttachmentShape == TierESysEnterAttachmentShape.MAIN_PLUS_AUDIT

/** Native layout of `union bpf_attr` for `BPF_BTF_LOAD`. */
internal object BpfBtfLoadLayout {
    const val SIZE: Long = 40
    const val BTF: Long = 0
    const val LOG_BUFFER: Long = 8
    const val BTF_SIZE: Long = 16
    const val LOG_SIZE: Long = 20
    const val LOG_LEVEL: Long = 24
}

/** Native layout of the `raw_tracepoint` arm of `union bpf_attr`. */
internal object BpfRawTracepointOpenLayout {
    const val SIZE: Long = 24
    const val NAME: Long = 0
    const val PROGRAM_FD: Long = 8
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
    const val CONFIG: Long = 8
    const val SAMPLE_PERIOD: Long = 16
    const val UPROBE_PATH: Long = 56
    const val UPROBE_OFFSET: Long = 64
}
