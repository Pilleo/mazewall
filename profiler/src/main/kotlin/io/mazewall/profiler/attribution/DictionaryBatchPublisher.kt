package io.mazewall.profiler.attribution

/**
 * Coordinates the prepare -> sink -> commit/rollback protocol.
 *
 * There is deliberately no asynchronous state hidden here: a caller either
 * receives a committed batch or the encoder still owns the exact same bytes
 * and definitions for a later retry.
 */
public class DictionaryBatchPublisher(
    private val encoder: StackDictionaryStreamEncoder,
    private val sink: DictionaryBatchSink,
    private val onCommitted: (EncodedDictionaryBatch) -> Unit = {},
) {
    private var evidence = DictionaryTransportEvidence()
    private var terminated = false

    public val transportEvidence: DictionaryTransportEvidence
        get() = evidence

    public fun publishAvailable(maxCount: Int): DictionaryPublicationStatus {
        check(!terminated) { "dictionary publisher is terminated" }
        val batch = encoder.prepareBatch(maxCount) ?: return DictionaryPublicationStatus.NO_BATCH
        return when (val result = sink.write(batch)) {
            DictionarySinkResult.Committed -> {
                encoder.commit(batch)
                onCommitted(batch)
                evidence = evidence.copy(committedBatches = evidence.committedBatches + 1)
                DictionaryPublicationStatus.COMMITTED
            }
            DictionarySinkResult.RetryableFailure -> {
                encoder.rollback(batch)
                evidence = evidence.copy(retryableFailures = evidence.retryableFailures + 1)
                DictionaryPublicationStatus.RETRYABLE_FAILURE
            }
            is DictionarySinkResult.AmbiguousDelivery -> {
                encoder.rollback(batch)
                evidence = evidence.copy(ambiguousFailures = evidence.ambiguousFailures + 1)
                terminated = true
                DictionaryPublicationStatus.TERMINAL_FAILURE
            }
            is DictionarySinkResult.TerminalFailure -> {
                encoder.rollback(batch)
                evidence = evidence.copy(terminalFailures = evidence.terminalFailures + 1)
                terminated = true
                DictionaryPublicationStatus.TERMINAL_FAILURE
            }
        }
    }

    /**
     * Terminates publication fail-closed. A caller that prepared a batch but
     * has not delivered it must call this method; no watermark is advanced.
     */
    public fun terminate() {
        if (terminated) return
        if (encoder.hasInFlightBatch || encoder.pendingCount > 0) {
            evidence = evidence.copy(abandonedBatches = evidence.abandonedBatches + 1)
        }
        terminated = true
    }
}
