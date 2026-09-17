package io.mazewall.profiler.tierE.stress

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TierECommonFileWorkloadTest {
    @Test
    fun `shared workload reports deterministic completed operations`() {
        val first = TierECommonFileWorkload.run(4)
        val second = TierECommonFileWorkload.run(4)

        assertEquals(4, first.completedOperations)
        assertEquals(first.checksum, second.checksum)
        assertTrue(first.workloadNanos > 0)
    }

    @Test
    fun `batch execution preserves the workload contract across warmup and measured batches`() {
        val batches = TierECommonFileWorkload.runBatches(warmups = 2, batches = 3, iterations = 4)

        assertEquals(5, batches.size)
        assertEquals(listOf("WARMUP", "WARMUP", "MEASURED", "MEASURED", "MEASURED"), batches.map { it.phase.name })
        assertTrue(batches.all { it.result.completedOperations == 4 })
        assertEquals(1, batches.map { it.result.checksum }.distinct().size)
    }

    @Test
    fun `write oracle assigns a distinct syscall-visible operation id to every batch operation`() {
        val expected = TierEWriteOracleWorkload.expectedOperationIds(warmups = 2, batches = 3, iterations = 4)
        val batches = TierEWriteOracleWorkload.runBatches(warmups = 2, batches = 3, iterations = 4)

        assertEquals(20, expected.size)
        assertEquals(5, batches.size)
        assertEquals(20, batches.sumOf { it.result.completedOperations })
    }
}
