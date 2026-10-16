package io.mazewall.profiler.internal.attribution
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import io.mazewall.profiler.tierE.TierERetainedStateBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StackDictionaryRetentionBudgetTest {
    @Test
    fun `dictionary staging rejects entries when the shared budget is exhausted`() {
        val encoder = StackDictionaryStreamEncoder(maxPendingEntries = 4)
        val budget = TierERetainedStateBudget(limitBytes = 1)
        encoder.attachRetainedBudget(budget)

        encoder.enqueue(definition(1))

        assertEquals(0, encoder.pendingCount)
        assertEquals(1L, encoder.droppedCount)
        assertEquals(0L, budget.usedBytes())
        assertTrue(budget.wasExhausted())
    }

    @Test
    fun `committed dictionary staging releases the shared budget`() {
        val encoder = StackDictionaryStreamEncoder(maxPendingEntries = 4)
        val budget = TierERetainedStateBudget()
        encoder.attachRetainedBudget(budget)

        encoder.enqueue(definition(1))
        assertTrue(budget.usedBytes() > 0)

        encoder.drainEncoded()

        assertEquals(0L, budget.usedBytes())
        assertEquals(1L, encoder.watermark)
    }

    private fun definition(id: Long): StackDefinition =
        StackDefinition(
        StackTraceId(id),
        listOf(ManagedFrame("app", "Foo", "bar", "()V", 1L, ManagedFrameKind.JAVA)),
        StackCaptureQuality.COMPLETE,
    )
}
