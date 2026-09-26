package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TierEBenchmarkPhaseGateTest {
    @Test
    fun `three native samples use the median and fail above the baseline limit`() {
        val baseline = mapOf("get_stack_trace_ns" to 100L, "raw_key_ns" to 100L, "logical_metadata_ns" to 100L)
        val observations = listOf(100L, 110L, 200L).joinToString("\n") { ns ->
            "tier-e-benchmark: get_stack_trace_ns=$ns raw_key_ns=100 logical_metadata_ns=100"
        }
        assertEquals(110L, TierEBenchmarkPhaseGate.check("full", observations, baseline)["get_stack_trace_ns"])
        val exceeded = observations.replace("110 raw_key_ns", "126 raw_key_ns")
        assertThrows(IllegalArgumentException::class.java) { TierEBenchmarkPhaseGate.check("full", exceeded, baseline) }
    }
}
