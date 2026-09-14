@file:Suppress("UnusedVariable")

package io.mazewall.profiler.internal.attribution

/**
 * Coordinates the prepare -> sink -> commit/rollback protocol.
 *
 * There is deliberately no asynchronous state hidden here: a caller either
 * receives a committed batch or the encoder still owns the exact same bytes
 * and definitions for a later retry.
 */
internal class DictionaryBatchPublisher(
    private val encoder: StackDictionaryStreamEncoder,
    private val sink: DictionaryBatchSink,
    private val onCommitted: (EncodedDictionaryBatch) -> Unit = {},
) {
    private var evidence = DictionaryTransportEvidence()
    private var publicationMetrics = SnapshotPublicationMetrics()
    private var terminated = false

    public val transportEvidence: DictionaryTransportEvidence
        get() = evidence

    public val metrics: SnapshotPublicationMetrics
        get() = publicationMetrics

    public fun publishAvailable(maxCount: Int): DictionaryPublicationStatus {
        check(!terminated) { "dictionary publisher is terminated" }
        val batch = encoder.prepareBatch(maxCount) ?: return DictionaryPublicationStatus.NO_BATCH
        val started = System.nanoTime()
        return when (val result = sink.write(batch)) {
            DictionarySinkResult.Committed -> {
                encoder.commit(batch)
                onCommitted(batch)
                evidence = evidence.copy(committedBatches = evidence.committedBatches + 1)
                publicationMetrics = publicationMetrics.copy(
                    serializedBytes = publicationMetrics.serializedBytes + batch.bytes.size,
                    publicationCount = publicationMetrics.publicationCount + 1,
                    publicationLatencyNanos = publicationMetrics.publicationLatencyNanos + (System.nanoTime() - started),
                )
                DictionaryPublicationStatus.COMMITTED
            }
            DictionarySinkResult.RetryableFailure -> {
                encoder.rollback(batch)
                evidence = evidence.copy(retryableFailures = evidence.retryableFailures + 1)
                publicationMetrics = publicationMetrics.copy(
                    publicationCount = publicationMetrics.publicationCount + 1,
                    publicationLatencyNanos = publicationMetrics.publicationLatencyNanos + (System.nanoTime() - started),
                )
                DictionaryPublicationStatus.RETRYABLE_FAILURE
            }
            is DictionarySinkResult.AmbiguousDelivery -> {
                encoder.rollback(batch)
                evidence = evidence.copy(ambiguousFailures = evidence.ambiguousFailures + 1)
                publicationMetrics = publicationMetrics.copy(
                    publicationCount = publicationMetrics.publicationCount + 1,
                    publicationLatencyNanos = publicationMetrics.publicationLatencyNanos + (System.nanoTime() - started),
                )
                terminated = true
                DictionaryPublicationStatus.TERMINAL_FAILURE
            }
            is DictionarySinkResult.TerminalFailure -> {
                encoder.rollback(batch)
                evidence = evidence.copy(terminalFailures = evidence.terminalFailures + 1)
                publicationMetrics = publicationMetrics.copy(
                    publicationCount = publicationMetrics.publicationCount + 1,
                    publicationLatencyNanos = publicationMetrics.publicationLatencyNanos + (System.nanoTime() - started),
                )
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
