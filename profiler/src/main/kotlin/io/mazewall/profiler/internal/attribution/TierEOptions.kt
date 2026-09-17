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

/** Explicit Tier E session configuration. The full stream remains the safe default. */
internal data class TierEOptions(
    val emissionMode: TierEEmissionMode = TierEEmissionMode.FULL_STREAM,
    val stackTransport: TierEStackTransport = TierEStackTransport.DICTIONARY,
    val maxPendingRecords: Int = DEFAULT_RECORD_LIMIT,
    val maxRetainedRecords: Int = DEFAULT_RECORD_LIMIT,
    val maxRetainedBytes: Long = DEFAULT_RETAINED_BYTES,
    /** Enables benchmark-only collector phase timing; ordinary sessions do not read the clock. */
    val benchmarkPhases: Boolean = false,
) {
    init {
        require(maxPendingRecords > 0) { "maxPendingRecords must be positive" }
        require(maxRetainedRecords > 0) { "maxRetainedRecords must be positive" }
        require(maxRetainedBytes > 0) { "maxRetainedBytes must be positive" }
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
