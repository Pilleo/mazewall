package io.mazewall.profiler.internal.attribution

/** Result of attempting to publish one prepared dictionary batch. */
internal sealed interface DictionarySinkResult {
    /** The receiver crossed the sink's documented acknowledgement boundary. */
    public data object Committed : DictionarySinkResult

    /** Replay is safe because no externally visible effect occurred or delivery is idempotent. */
    public data object RetryableFailure : DictionarySinkResult

    /** Delivery may have crossed the receiver boundary; replay is therefore not safe. */
    public data class AmbiguousDelivery(
        public val reason: String,
    ) : DictionarySinkResult

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
internal fun interface DictionaryBatchSink {
    public fun write(batch: EncodedDictionaryBatch): DictionarySinkResult
}

internal enum class DictionaryPublicationStatus {
    NO_BATCH,
    COMMITTED,
    RETRYABLE_FAILURE,
    TERMINAL_FAILURE,
}

/** Machine-readable transport evidence kept separate from attribution coverage. */
internal data class DictionaryTransportEvidence(
    public val committedBatches: Long = 0,
    public val retryableFailures: Long = 0,
    public val terminalFailures: Long = 0,
    public val ambiguousFailures: Long = 0,
    public val abandonedBatches: Long = 0,
) {
    public val complete: Boolean
        get() = terminalFailures == 0L && ambiguousFailures == 0L && abandonedBatches == 0L
}
