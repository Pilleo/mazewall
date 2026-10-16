package io.mazewall.profiler.internal.attribution

import io.mazewall.profiler.tierE.TierERetainedStateBudget
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TierENativeLiveSnapshotConsumerTest {
    @Test
    fun `live reader commits once, rejects disappeared prefix, and never counts duplicate definitions`() {
        val session = SessionEpoch(0x4d5a)
        val stack = StackDefinition(StackTraceId(7), emptyList(), StackCaptureQuality.COMPLETE)
        val invocation = InvocationDefinition(InvocationId(55), null, stack.stackTraceId)
        val snapshot = AgentDefinitionFile.Contents(
            listOf(stack),
            listOf(invocation),
            0,
            publication = SnapshotPublication(1, 55, false, session),
            sourceByteLength = 100,
            sourceSha256 = "a".repeat(64),
        )
        var now = 0L
        var read = 0
        val accepted = mutableListOf<Any>()
        val failures = mutableListOf<Exception>()
        val budget = TierERetainedStateBudget(64L * 1024L)
        val consumer = TierENativeLiveSnapshotConsumer(
            session = session,
            maxDefinitions = 10,
            budget = budget,
            read = { if (++read <= 2) snapshot else null },
            acceptStack = accepted::add,
            acceptInvocation = accepted::add,
            onAgentLoss = { error("unexpected native loss") },
            onFailure = failures::add,
            nowNanos = { now },
        )

        consumer.poll()
        assertEquals(1, consumer.publicationCount)
        assertEquals(listOf(stack, invocation), accepted)
        assertEquals(0, budget.bytesFor(TierERetainedStateBudget.Category.SNAPSHOT_BUFFERS))
        now += 100_000_000L
        consumer.poll()
        assertEquals(1, consumer.publicationCount)
        assertEquals(2, accepted.size)
        now += 100_000_000L
        consumer.poll()
        assertEquals(1, failures.size)
        assertTrue(failures.single().message!!.contains("disappeared"))
        now += 100_000_000L
        consumer.poll()
        assertEquals(3, read)
    }
}
