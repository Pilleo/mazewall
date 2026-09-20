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

/** Explicit Tier E session configuration. The full stream remains the safe default. */
internal data class TierEOptions(
    val emissionMode: TierEEmissionMode = TierEEmissionMode.FULL_STREAM,
    val stackTransport: TierEStackTransport = TierEStackTransport.DICTIONARY,
    /** Explicit declared capture domain; full capture remains the default. */
    val captureProfile: TierECaptureProfile = TierECaptureProfile.Full,
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
    /** Primitive owned argument storage is the production path; boxed lists remain benchmark-only. */
    val ownedArgumentStorage: Boolean = true,
    /** Read-only ready transfer is production behavior; copying remains a paired benchmark control. */
    val readyTransferMode: TierEReadyTransferMode = TierEReadyTransferMode.READ_ONLY,
    /** Session draining appends directly to its owned batch; temporary lists remain a benchmark control. */
    val collectorResultMode: TierECollectorResultMode = TierECollectorResultMode.APPEND_TARGET,
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
