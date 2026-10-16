package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TierEBenchmarkPhaseGateTest {
    @Test
    fun `version two requires all cache lookup work and exact benchmark captures`() {
        val baseline = mapOf("get_stack_trace_ns" to 100L, "raw_key_ns" to 100L, "logical_metadata_ns" to 300L)

        fun sample(
            key: Int,
            lookup: Int,
            logical: Int,
            captures: Int = 514,
            hits: Int = 504,
        ): String =
            "15: tier-e-benchmark: phase_contract_version=2 get_stack_trace_ns=90 raw_key_ns=$key logical_metadata_ns=$logical " +
            "canonical_cache_lookup_ns=$lookup emission_mode=full-stream cache_hits=$hits cache_misses=10\n" +
                "15: mazewall-stack-agent: native-binds=248 proxied-binds=4 stack-captures=$captures stack-failures=0\n" +
                "15: agent-workload: workloadNs=1000\n"
        val passing = sample(50, 150, 100) + sample(55, 200, 110) + sample(50, 600, 100)
        val result = TierEBenchmarkPhaseGate.comparable("full", passing, baseline)
        assertEquals(365L, result.getValue("key_lookup_logical_total_ns"))
        assertThrows(IllegalArgumentException::class.java) {
            TierEBenchmarkPhaseGate.comparable("full", sample(50, 500, 100).repeat(3), baseline)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TierEBenchmarkPhaseGate.comparable("full", passing.replace("canonical_cache_lookup_ns=", "missing_lookup_ns="), baseline)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TierEBenchmarkPhaseGate.comparable("full", passing.replace("stack-captures=514", "stack-captures=513"), baseline)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TierEBenchmarkPhaseGate.comparable("full", passing.replace("cache_hits=504", "cache_hits=0"), baseline)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TierEBenchmarkPhaseGate.comparable("unique", passing, baseline)
        }
        val unique = passing.replace("emission_mode=full-stream", "emission_mode=unique-stack-syscall")
        assertEquals(365L, TierEBenchmarkPhaseGate.comparable("unique", unique, baseline)["key_lookup_logical_total_ns"])
        assertThrows(IllegalArgumentException::class.java) {
            TierEBenchmarkPhaseGate.check("full", passing, baseline)
        }
    }

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
