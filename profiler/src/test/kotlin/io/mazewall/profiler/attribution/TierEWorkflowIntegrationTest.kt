package io.mazewall.profiler.attribution

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Composes the collector and transactional dictionary transport at one workflow boundary. */
class TierEWorkflowIntegrationTest {
    @Test
    fun `retry then commit resolves and acknowledges exactly one unique edge`() {
        val collector = AttributionCollector(TierEEmissionMode.UNIQUE_STACK_SYSCALL)
        val invocation = InvocationDefinition(InvocationId(901), null, StackTraceId(77))
        val stack = StackDefinition(
            StackTraceId(77),
            listOf(ManagedFrame("app", "Service", "open", "()V", 12, ManagedFrameKind.JAVA)),
            StackCaptureQuality.COMPLETE,
        )
        val observation = SyscallAttribution(
            task = TaskIdentity(11, 22, 33),
            taskSequence = 44,
            syscallNumber = 257,
            args = List(6) { 0 },
            invocationId = InvocationId(901),
            attributionStatus = AttributionStatus.PENDING_DICTIONARY,
        )
        collector.accept(invocation)
        collector.accept(observation)

        val encoder = StackDictionaryStreamEncoder(maxPendingEntries = 2)
        encoder.enqueue(stack)
        var attempts = 0
        var resolved = emptyList<ResolvedSyscall>()
        val publisher = DictionaryBatchPublisher(
            encoder,
            DictionaryBatchSink {
                attempts++
                if (attempts == 1) DictionarySinkResult.RetryableFailure else DictionarySinkResult.Committed
            },
        ) { batch ->
            resolved = batch.definitions.flatMap(collector::accept)
        }

        assertEquals(DictionaryPublicationStatus.RETRYABLE_FAILURE, publisher.publishAvailable(1))
        assertEquals(0L, encoder.watermark)
        assertEquals(DictionaryPublicationStatus.COMMITTED, publisher.publishAvailable(1))
        assertEquals(77L, encoder.watermark)
        assertEquals(1, resolved.size)
        collector.acknowledgeEmission(resolved.single())
        assertTrue(collector.accept(observation.copy(taskSequence = 45)).isEmpty())
    }

    @Test
    fun `terminal transport failure preserves watermark and reports incomplete evidence`() {
        val encoder = StackDictionaryStreamEncoder()
        encoder.enqueue(
            StackDefinition(
                StackTraceId(88),
                listOf(ManagedFrame("app", "Service", "close", "()V", 13, ManagedFrameKind.JAVA)),
                StackCaptureQuality.COMPLETE,
            ),
        )
        val publisher = DictionaryBatchPublisher(
            encoder,
            DictionaryBatchSink { DictionarySinkResult.TerminalFailure("daemon unavailable") },
        )

        assertEquals(DictionaryPublicationStatus.TERMINAL_FAILURE, publisher.publishAvailable(1))
        assertEquals(0L, encoder.watermark)
        assertEquals(1, encoder.pendingCount)
        assertFalse(publisher.transportEvidence.complete)
    }
}
