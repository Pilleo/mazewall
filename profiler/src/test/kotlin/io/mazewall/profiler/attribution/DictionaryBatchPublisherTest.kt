package io.mazewall.profiler.attribution

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DictionaryBatchPublisherTest {
    @Test
    fun `retry keeps the exact batch and watermark unchanged until commit`() {
        val encoder = StackDictionaryStreamEncoder(maxPendingEntries = 4)
        encoder.enqueue(definition(10L))
        encoder.enqueue(definition(11L))
        val writes = mutableListOf<ByteArray>()
        var attempt = 0
        val publisher = DictionaryBatchPublisher(
            encoder,
            DictionaryBatchSink { batch ->
            writes += batch.bytes
            attempt += 1
            if (attempt == 1) DictionarySinkResult.RetryableFailure else DictionarySinkResult.Committed
        },
        )

        assertEquals(DictionaryPublicationStatus.RETRYABLE_FAILURE, publisher.publishAvailable(2))
        assertEquals(2, encoder.pendingCount)
        assertEquals(0L, encoder.watermark)
        assertEquals(DictionaryPublicationStatus.COMMITTED, publisher.publishAvailable(2))
        assertEquals(0, encoder.pendingCount)
        assertEquals(11L, encoder.watermark)
        assertEquals(2, writes.size)
        assertContentEquals(writes[0], writes[1])
    }

    @Test
    fun `termination records an abandoned prepared batch without committing it`() {
        val encoder = StackDictionaryStreamEncoder()
        encoder.enqueue(definition(21L))
        val publisher = DictionaryBatchPublisher(encoder, DictionaryBatchSink { DictionarySinkResult.Committed })

        requireNotNull(encoder.prepareBatch())
        publisher.terminate()

        assertTrue(publisher.transportEvidence.abandonedBatches == 1L)
        assertEquals(1, encoder.pendingCount)
        assertEquals(0L, encoder.watermark)
    }

    @Test
    fun `terminal sink failure preserves definitions and marks transport incomplete`() {
        val encoder = StackDictionaryStreamEncoder()
        encoder.enqueue(definition(31L))
        val publisher = DictionaryBatchPublisher(
            encoder,
            DictionaryBatchSink { DictionarySinkResult.TerminalFailure("sink closed") },
        )

        assertEquals(DictionaryPublicationStatus.TERMINAL_FAILURE, publisher.publishAvailable(1))
        assertEquals(1, encoder.pendingCount)
        assertEquals(0L, encoder.watermark)
        assertEquals(false, publisher.transportEvidence.complete)
        assertEquals(1L, publisher.transportEvidence.terminalFailures)
    }

    private fun definition(id: Long): StackDefinition =
        StackDefinition(
        StackTraceId(id),
        listOf(ManagedFrame("app", "Foo", "work", "()V", id, ManagedFrameKind.JAVA)),
        StackCaptureQuality.COMPLETE,
    )
}
