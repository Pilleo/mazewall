package io.mazewall.profiler.tierE.stress

import io.mazewall.profiler.tierE.ringbuf.TierERingDecoderMicrobenchmark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TierERingDecoderMicrobenchmarkTest {
    @Test
    fun `decoder diagnostic variants retain the same fixed-record checksum`() {
        val results = TierERingDecoderMicrobenchmark.Variant.entries.associateWith { variant ->
            TierERingDecoderMicrobenchmark.runSample(variant, iterations = 128)
        }

        assertEquals(
            1,
            results.values
            .map { it.checksum }
            .toSet()
            .size,
        )
        assertTrue(results.values.all { it.elapsedNanos > 0 })
    }
}
