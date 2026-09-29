package io.mazewall.profiler.internal.attribution

/** Controls whether Tier E reports every syscall or a unique coverage edge. */
internal enum class TierEEmissionMode {
    /** Preserve the complete raw syscall stream. */
    FULL_STREAM,

    /** Emit one representative observation per exact logical stack and syscall number. */
    UNIQUE_STACK_SYSCALL,
}

/** Controls how exact stack definitions travel from the native agent to the collector. */
internal enum class TierEStackTransport {
    /** Publish each canonical stack once and reference it from invocation records. */
    DICTIONARY,

    /** Repeat the captured stack alongside every successful native invocation. */
    INLINE_PER_INVOCATION,
}

/** Selects the owned userspace decoding path; legacy bytes exist only as a benchmark control. */
internal enum class TierERingDecodeMode {
    DIRECT,
    LEGACY_BYTES,
}

/** Benchmark-only choice of the immutable argument-list construction used by direct ring decoding. */
internal enum class TierERingArgumentListMode {
    IMMUTABLE_FACTORY,
    LAMBDA_COPY,
}

/** Benchmark-only choice of when an event exposes its boxed argument compatibility view. */
internal enum class TierERingArgumentViewMode {
    LAZY,
    EAGER_CONTROL,
}

/** Controls reuse of one immutable task identity across consecutive records for the same TID. */
internal enum class TierETaskIdentityMode {
    PER_EVENT,
    REUSE_CONSECUTIVE,
}

/** Controls whether already-complete full-stream joins bypass temporary pending structures. */
internal enum class TierEKnownJoinMode {
    QUEUED_CONTROL,
    DIRECT_KNOWN,
}

/** Benchmark-only real-BPF-map control; disabling submission is never valid Tier E evidence. */
internal enum class TierERingSubmissionMode {
    ENABLED,
    BENCHMARK_DISABLED,
}

/** Benchmark-only selection of the detached ready-observation transfer implementation. */
internal enum class TierEReadyTransferMode {
    READ_ONLY,
    COPY_CONTROL,
}

/** Benchmark-only selection of how session draining receives collector results. */
internal enum class TierECollectorResultMode {
    TEMPORARY_LIST,
    APPEND_TARGET,
}

/** Attachment mechanism for the selected `raw_syscalls/sys_enter` program. */
internal enum class TierESysEnterAttachmentBackend {
    /** Direct raw tracepoint on x86_64; compatibility perf attachment elsewhere. */
    AUTO,

    /** Direct raw tracepoint; avoids CPU-global perf sampling. */
    DIRECT_RAW_TRACEPOINT,

    /** Legacy CPU-global perf tracepoint attachment for explicit compatibility use. */
    PERF_TRACEPOINT_COMPAT,
}

/**
 * Benchmark-only control for the optional kernel diagnostic coverage map.
 *
 * The map never owns unique-edge suppression: acknowledgement and deduplication
 * remain collector-authoritative. Production preserves the existing update path.
 */
internal enum class TierEKernelCoverageMode {
    /** Omit coverage-map allocation and all coverage-map helper calls. */
    NONE,

    /** Retain only the coverage-map lookup, without changing map state. */
    DIAGNOSTIC_LOOKUP,

    /** Retain the existing bounded diagnostic map update. */
    DIAGNOSTIC_UPDATE,
}

/** Benchmark-only sys_enter attachment set used to isolate pre-admission omissions. */
internal enum class TierESysEnterAttachmentShape {
    /** The existing Tier E program plus the descriptor audit program. */
    MAIN_PLUS_AUDIT,

    /** Descriptor-keyed audit program without the main Tier E sys_enter program. */
    DESCRIPTOR_AUDIT_ONLY,

    /** Counter-only audit program without the main Tier E sys_enter program. */
    MINIMAL_AUDIT_ONLY,

    /** The main Tier E program plus a target-gated counter audit without descriptor mutation. */
    MINIMAL_COUNTER_ONLY,
}

/**
 * Native marker ABI version selected for one session.
 *
 * V1 carries `(invocationId, flags)` and is the default. V2 carries
 * `(invocationId, stackTraceId, flags)` so the kernel can key a retained edge
 * by canonical stack identity. V2 is experimental benchmark-only until qualified.
 */
internal enum class MarkerVersion {
    V1,
    V2,
}

/**
 * Opt-in retained-edge recovery mode.
 *
 * DISABLED preserves the existing generated program without coverage-recovery accesses.
 * SHADOW resolves and compares map candidates without adding recovered coverage.
 * RECOVER may add coverage only after successful retained-output acknowledgement.
 */
internal enum class CoverageRecoveryMode {
    DISABLED,
    SHADOW,
    RECOVER,
    ;

    companion object {
        const val DEFAULT_MAP_CAPACITY: Int = 4_096
        const val MAX_SCAN_BATCH: Int = 256
        const val DEFAULT_SCAN_INTERVAL_MILLIS: Long = 100L
    }
}

/** Explicit Tier E session configuration. The full stream remains the safe default. */
internal data class TierEOptions(
    val emissionMode: TierEEmissionMode = TierEEmissionMode.FULL_STREAM,
    val stackTransport: TierEStackTransport = TierEStackTransport.DICTIONARY,
    /** Explicit declared capture domain; full capture remains the default. */
    val captureProfile: TierECaptureProfile = TierECaptureProfile.Full,
    /** Direct raw tracepoint attachment is selected automatically on supported x86_64 hosts. */
    val sysEnterAttachmentBackend: TierESysEnterAttachmentBackend = TierESysEnterAttachmentBackend.AUTO,
    /** Optional kernel-only unique-edge diagnostic work; update preserves production behavior. */
    val kernelCoverageMode: TierEKernelCoverageMode = TierEKernelCoverageMode.DIAGNOSTIC_UPDATE,
    /** Attachment diagnostic control; production always installs the normal main program. */
    val benchmarkSysEnterAttachmentShape: TierESysEnterAttachmentShape = TierESysEnterAttachmentShape.MAIN_PLUS_AUDIT,
    val maxPendingRecords: Int = DEFAULT_RECORD_LIMIT,
    val maxRetainedRecords: Int = DEFAULT_RECORD_LIMIT,
    val maxRetainedBytes: Long = DEFAULT_RETAINED_BYTES,
    /** Enables benchmark-only collector phase timing; ordinary sessions do not read the clock. */
    val benchmarkPhases: Boolean = false,
    /** Enables benchmark-only allocation accounting around whole collector phases. */
    val benchmarkAllocationAccounting: Boolean = false,
    /** Legacy bytes preserve the existing default; direct decode is benchmark-only until accepted. */
    val ringDecodeMode: TierERingDecodeMode = TierERingDecodeMode.LEGACY_BYTES,
    /** The factory path is production behavior; lambda-copy exists only as a paired benchmark control. */
    val ringArgumentListMode: TierERingArgumentListMode = TierERingArgumentListMode.IMMUTABLE_FACTORY,
    /** Lazy compatibility views are production behavior; eager boxing exists only as a paired benchmark control. */
    val ringArgumentViewMode: TierERingArgumentViewMode = TierERingArgumentViewMode.LAZY,
    /** One-entry reuse is the production default; per-event construction remains a paired benchmark control. */
    val taskIdentityMode: TierETaskIdentityMode = TierETaskIdentityMode.REUSE_CONSECUTIVE,
    /** Queued joins are the baseline; direct known-definition joining is a paired benchmark control. */
    val knownJoinMode: TierEKnownJoinMode = TierEKnownJoinMode.QUEUED_CONTROL,
    /** Keeps marker and task-storage work but omits ring reservation/submission for cost attribution only. */
    val ringSubmissionMode: TierERingSubmissionMode = TierERingSubmissionMode.ENABLED,
    /**
     * Emits a zero-invocation ring record when a target syscall has no task-storage state.
     * This diagnostic mode is benchmark-only: normal collection accounts for the condition
     * through its bounded kernel counter without widening the ambient event stream.
     */
    val benchmarkEmitTaskStorageUnavailableEvents: Boolean = false,
    /**
     * Emits zero-invocation records for every marker-state loss branch. This is a benchmark-only
     * correlation probe; production keeps these conditions as bounded aggregate counters.
     */
    val benchmarkEmitMarkerLossEvents: Boolean = false,
    /**
     * Attaches a second counter-only raw-syscalls program for a declared set of benchmark
     * operation descriptors. It emits no ring records and is excluded from capacity evidence.
     */
    val benchmarkRawSysEnterAudit: Boolean = false,
    /** Counts declared openat descriptors after exact marker/task-storage admission; benchmark-only. */
    val benchmarkExactAdmissionAudit: Boolean = false,
    /** Diagnostic-only per-invocation marker witness before raw/main FD gates. */
    val benchmarkInvocationIdWitness: Boolean = false,
    /** Primitive owned argument storage is the production path; boxed lists remain benchmark-only. */
    val ownedArgumentStorage: Boolean = true,
    /** Read-only ready transfer is production behavior; copying remains a paired benchmark control. */
    val readyTransferMode: TierEReadyTransferMode = TierEReadyTransferMode.READ_ONLY,
    /** Session draining appends directly to its owned batch; temporary lists remain a benchmark control. */
    val collectorResultMode: TierECollectorResultMode = TierECollectorResultMode.APPEND_TARGET,
    /**
     * Opt-in retained-edge recovery. DISABLED preserves the existing program without
     * coverage-recovery accesses. SHADOW/RECOVER are experimental and benchmark-only
     * until qualified.
     */
    val coverageRecoveryMode: CoverageRecoveryMode = CoverageRecoveryMode.DISABLED,
    /** Distinct-edge capacity of the retained recovery map; recorded in the manifest. */
    val coverageRecoveryMapCapacity: Int = CoverageRecoveryMode.DEFAULT_MAP_CAPACITY,
    /** Native marker ABI version; exactly one version is active per session. */
    val markerVersion: MarkerVersion = MarkerVersion.V1,
    /** Experimental admission is distinct from benchmark phase instrumentation. */
    val experimentalCoverage: Boolean = false,
    /** Controlled fault: drop selected ring events after retained-map insertion. Benchmark only. */
    val benchmarkDropRingSyscallNumber: Int? = null,
    /** Opt-in target-wide syscall history, independent of native marker attribution. */
    val allTargetCapture: Boolean = false,
) {
    init {
        require(maxPendingRecords > 0) { "maxPendingRecords must be positive" }
        require(maxRetainedRecords > 0) { "maxRetainedRecords must be positive" }
        require(maxRetainedBytes > 0) { "maxRetainedBytes must be positive" }
        require(benchmarkPhases || ringSubmissionMode == TierERingSubmissionMode.ENABLED) {
            "ring submission may be disabled only in a benchmark-phase control"
        }
        require(benchmarkPhases || !benchmarkEmitTaskStorageUnavailableEvents) {
            "task-storage unavailable event emission is benchmark-only"
        }
        require(benchmarkPhases || !benchmarkEmitMarkerLossEvents) {
            "marker-loss event emission is benchmark-only"
        }
        require(benchmarkPhases || !benchmarkRawSysEnterAudit) {
            "raw sys_enter auditing is benchmark-only"
        }
        require(benchmarkPhases || !benchmarkExactAdmissionAudit) {
            "exact-admission auditing is benchmark-only"
        }
        require(
            !benchmarkInvocationIdWitness ||
                (
                    benchmarkPhases &&
                    benchmarkRawSysEnterAudit &&
                    benchmarkExactAdmissionAudit &&
                    benchmarkSysEnterAttachmentShape == TierESysEnterAttachmentShape.MAIN_PLUS_AUDIT
                ),
        ) { "invocation-ID witness requires paired benchmark raw and main audits" }
        require(benchmarkPhases || kernelCoverageMode == TierEKernelCoverageMode.DIAGNOSTIC_UPDATE) {
            "kernel coverage variants are benchmark-only"
        }
        require(benchmarkPhases || benchmarkSysEnterAttachmentShape == TierESysEnterAttachmentShape.MAIN_PLUS_AUDIT) {
            "sys_enter attachment shapes are benchmark-only"
        }
        require(benchmarkPhases || !benchmarkAllocationAccounting) {
            "allocation accounting may be enabled only in a benchmark-phase session"
        }
        require(benchmarkPhases || readyTransferMode == TierEReadyTransferMode.READ_ONLY) {
            "copying ready transfer is benchmark-only"
        }
        require(benchmarkPhases || ringArgumentViewMode == TierERingArgumentViewMode.LAZY) {
            "eager ring argument views are benchmark-only"
        }
        require(benchmarkPhases || knownJoinMode == TierEKnownJoinMode.QUEUED_CONTROL) {
            "direct known joins are benchmark-only"
        }
        require(benchmarkPhases || collectorResultMode == TierECollectorResultMode.APPEND_TARGET) {
            "temporary collector results are benchmark-only"
        }
        require(coverageRecoveryMapCapacity in 1..CoverageRecoveryMode.DEFAULT_MAP_CAPACITY) {
            "coverageRecoveryMapCapacity must be 1..${CoverageRecoveryMode.DEFAULT_MAP_CAPACITY}"
        }
        require(benchmarkPhases || experimentalCoverage || coverageRecoveryMode == CoverageRecoveryMode.DISABLED) {
            "coverage recovery is experimental benchmark-only"
        }
        require(benchmarkPhases || experimentalCoverage || markerVersion == MarkerVersion.V1) {
            "marker v2 is experimental benchmark-only"
        }
        require(coverageRecoveryMode == CoverageRecoveryMode.DISABLED || markerVersion == MarkerVersion.V2) {
            "coverage recovery requires marker v2"
        }
        require(
            benchmarkDropRingSyscallNumber == null ||
            ((benchmarkPhases || experimentalCoverage) && coverageRecoveryMode != CoverageRecoveryMode.DISABLED && benchmarkDropRingSyscallNumber >= 0),
        ) { "ring fault injection requires an experimental recovery session and a nonnegative syscall" }
        require(
            !allTargetCapture ||
                (
                    emissionMode == TierEEmissionMode.FULL_STREAM &&
                    stackTransport == TierEStackTransport.DICTIONARY &&
                    markerVersion == MarkerVersion.V1 &&
                    coverageRecoveryMode == CoverageRecoveryMode.DISABLED &&
                    ringSubmissionMode == TierERingSubmissionMode.ENABLED &&
                    sysEnterAttachmentBackend != TierESysEnterAttachmentBackend.PERF_TRACEPOINT_COMPAT &&
                    !benchmarkPhases
                ),
        ) { "all-target capture requires full-stream v1 dictionary, direct raw tracepoint, and no benchmark controls" }
    }

    public companion object {
        public const val DEFAULT_RECORD_LIMIT: Int = 100_000
        public const val DEFAULT_RETAINED_BYTES: Long = 64L * 1024L * 1024L
    }
}

/** What the session may truthfully claim about its output. */
internal enum class CaptureGuarantee {
    FULL_STREAM,
    UNIQUE_EDGES,
    INCOMPLETE,
}
