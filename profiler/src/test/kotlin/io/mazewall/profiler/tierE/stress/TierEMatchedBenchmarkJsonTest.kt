package io.mazewall.profiler.tierE.stress

import kotlin.test.Test
import kotlin.test.assertEquals

class TierEMatchedBenchmarkJsonTest {
    @Test
    fun `artifact escaping keeps child control output valid JSON`() {
        assertEquals(
            "failure\\u0000\\t\\n\\r\\b\\f\\\"\\\\",
            TierEMatchedBenchmark.escape("failure\u0000\t\n\r\b\u000c\"\\"),
        )
    }
}
