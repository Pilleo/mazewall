package io.mazewall.profiler.tierE.ringbuf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TierERingMappedDrainMicrobenchmarkTest {
    @Test
    fun `mapped drain diagnostic retains expected records for every batch shape`() {
        val iterations = 8
        val results = TierERingMappedDrainMicrobenchmark.Variant.entries.associateWith { variant ->
            TierERingMappedDrainMicrobenchmark.runSample(variant, iterations)
        }

        assertEquals(0, results.getValue(TierERingMappedDrainMicrobenchmark.Variant.EMPTY).records)
        assertEquals(iterations.toLong(), results.getValue(TierERingMappedDrainMicrobenchmark.Variant.SINGLE).records)
        assertEquals(iterations.toLong() * 16, results.getValue(TierERingMappedDrainMicrobenchmark.Variant.BATCH_16).records)
        assertTrue(results.values.all { it.elapsedNanos > 0 })
    }
}
