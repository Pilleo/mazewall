package io.mazewall.profiler.attribution

/** Result of attempting to publish one prepared dictionary batch. */
public sealed interface DictionarySinkResult {
    public data object Committed : DictionarySinkResult

    public data object RetryableFailure : DictionarySinkResult

    public data class TerminalFailure(
        public val reason: String,
    ) : DictionarySinkResult
}

/**
 * Downstream boundary for dictionary definitions.
 *
 * The sink must not mutate the supplied batch. The encoder remains the owner of
 * the pending definitions until the caller observes [DictionarySinkResult.Committed].
 */
public fun interface DictionaryBatchSink {
    public fun write(batch: EncodedDictionaryBatch): DictionarySinkResult
}

public enum class DictionaryPublicationStatus {
    NO_BATCH,
    COMMITTED,
    RETRYABLE_FAILURE,
    TERMINAL_FAILURE,
}

/** Machine-readable transport evidence kept separate from attribution coverage. */
public data class DictionaryTransportEvidence(
    public val committedBatches: Long = 0,
    public val retryableFailures: Long = 0,
    public val terminalFailures: Long = 0,
    public val abandonedBatches: Long = 0,
) {
    public val complete: Boolean
        get() = terminalFailures == 0L && abandonedBatches == 0L
}
