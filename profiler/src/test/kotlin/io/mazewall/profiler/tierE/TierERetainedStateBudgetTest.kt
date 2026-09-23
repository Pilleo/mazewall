package io.mazewall.profiler.tierE
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TierERetainedStateBudgetTest {
    @Test
    fun `default budget is 64 MiB`() {
        assertEquals(64L * 1024L * 1024L, TierERetainedStateBudget().limitBytes)
    }

    @Test
    fun `reserve is bounded and records explicit exhaustion`() {
        val budget = TierERetainedStateBudget(10)

        assertTrue(budget.tryReserve(TierERetainedStateBudget.Category.PENDING_OBSERVATIONS, 7))
        assertFalse(budget.tryReserve(TierERetainedStateBudget.Category.SNAPSHOT_BUFFERS, 4))
        assertEquals(7, budget.usedBytes())
        assertEquals(7, budget.bytesFor(TierERetainedStateBudget.Category.PENDING_OBSERVATIONS))
        assertTrue(budget.wasExhausted())
    }

    @Test
    fun `release restores capacity without clearing exhaustion evidence`() {
        val budget = TierERetainedStateBudget(10)
        val category = TierERetainedStateBudget.Category.PREPARED_BATCHES

        assertTrue(budget.tryReserve(category, 10))
        assertFalse(budget.tryReserve(category, 1))
        budget.release(category, 6)

        assertEquals(4, budget.usedBytes())
        assertEquals(10, budget.peakUsedBytes())
        assertEquals(6, budget.remainingBytes())
        assertTrue(budget.tryReserve(TierERetainedStateBudget.Category.DICTIONARY_STAGING, 6))
        assertEquals(10, budget.peakUsedBytes())
        assertTrue(budget.wasExhausted())
    }
}
