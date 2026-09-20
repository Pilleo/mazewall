package io.mazewall.profiler.attribution

import io.mazewall.profiler.internal.attribution.TierEDrainSizeAccumulator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TierEDrainSizeAccumulatorTest {
    @Test
    fun `empty distribution reports zero conservative percentiles`() {
        assertEquals(
            Triple(0L, 0L, 0L),
            TierEDrainSizeAccumulator().snapshot().let { Triple(it.p50UpperRecords, it.p95UpperRecords, it.p99UpperRecords) },
        )
    }

    @Test
    fun `distribution reports bounded inclusive upper values`() {
        val accumulator = TierEDrainSizeAccumulator()
        listOf(1, 2, 3, 4, 5, 8, 9, 16, 17, 32).forEach(accumulator::record)

        val distribution = accumulator.snapshot()

        assertEquals(10, distribution.samples)
        assertEquals(7, distribution.p50UpperRecords)
        assertEquals(63, distribution.p95UpperRecords)
        assertEquals(63, distribution.p99UpperRecords)
    }

    @Test
    fun `zero drain size is rejected`() {
        assertFailsWith<IllegalArgumentException> { TierEDrainSizeAccumulator().record(0) }
    }
}
