package io.mazewall.profiler.internal.attribution
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
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

    @Test
    fun `partial write followed by reconnect replays identical bytes before commit`() {
        val encoder = StackDictionaryStreamEncoder(maxPendingEntries = 3)
        encoder.enqueue(definition(41L))
        encoder.enqueue(definition(42L))
        val writes = mutableListOf<ByteArray>()
        var connectionAttempts = 0
        val publisher = DictionaryBatchPublisher(
            encoder,
            DictionaryBatchSink { batch ->
                connectionAttempts++
                writes += batch.bytes
                if (connectionAttempts == 1) DictionarySinkResult.RetryableFailure else DictionarySinkResult.Committed
            },
        )

        assertEquals(DictionaryPublicationStatus.RETRYABLE_FAILURE, publisher.publishAvailable(2))
        assertEquals(2, encoder.pendingCount)
        assertEquals(0L, encoder.watermark)
        assertEquals(DictionaryPublicationStatus.COMMITTED, publisher.publishAvailable(2))

        assertEquals(2, connectionAttempts)
        assertContentEquals(writes[0], writes[1])
        assertEquals(0, encoder.pendingCount)
        assertEquals(42L, encoder.watermark)
    }

    @Test
    fun `daemon crash before commit leaves an explicit incomplete abandoned batch`() {
        val encoder = StackDictionaryStreamEncoder(maxPendingEntries = 2)
        encoder.enqueue(definition(51L))
        val publisher = DictionaryBatchPublisher(
            encoder,
            DictionaryBatchSink { DictionarySinkResult.TerminalFailure("daemon crashed") },
        )

        assertEquals(DictionaryPublicationStatus.TERMINAL_FAILURE, publisher.publishAvailable(1))
        publisher.terminate()

        assertEquals(1, encoder.pendingCount)
        assertEquals(0L, encoder.watermark)
        assertEquals(1L, publisher.transportEvidence.terminalFailures)
        assertEquals(false, publisher.transportEvidence.complete)
    }

    @Test
    fun `retry preserves the same opaque batch identity`() {
        val encoder = StackDictionaryStreamEncoder()
        encoder.enqueue(definition(61L))
        val first = requireNotNull(encoder.prepareBatch())
        encoder.rollback(first)

        val retry = requireNotNull(encoder.prepareBatch())

        assertEquals(first.batchId, retry.batchId)
        assertContentEquals(first.bytes, retry.bytes)
        encoder.commit(retry)
    }

    @Test
    fun `ambiguous delivery is terminal and cannot be retried`() {
        val encoder = StackDictionaryStreamEncoder()
        encoder.enqueue(definition(71L))
        val publisher = DictionaryBatchPublisher(
            encoder,
            DictionaryBatchSink { DictionarySinkResult.AmbiguousDelivery("acknowledgement lost") },
        )

        assertEquals(DictionaryPublicationStatus.TERMINAL_FAILURE, publisher.publishAvailable(1))
        assertEquals(1, encoder.pendingCount)
        assertEquals(0L, encoder.watermark)
        assertEquals(1L, publisher.transportEvidence.ambiguousFailures)
        assertEquals(false, publisher.transportEvidence.complete)
    }

    private fun definition(id: Long): StackDefinition =
        StackDefinition(
        StackTraceId(id),
        listOf(ManagedFrame("app", "Foo", "work", "()V", id, ManagedFrameKind.JAVA)),
        StackCaptureQuality.COMPLETE,
    )
}
