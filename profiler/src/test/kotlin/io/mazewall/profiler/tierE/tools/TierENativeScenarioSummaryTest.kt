package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TierENativeScenarioSummaryTest {
    @Test
    fun `diagnostic transport median requires three actual measurements`() {
        val samples = (1..3).joinToString("\n") { "tier-e-benchmark: serialization_ns=$it" }
        assertEquals(2L, TierENativeScenarioSummary.medianThree(samples, "serialization_ns"))
        assertThrows(IllegalArgumentException::class.java) {
            TierENativeScenarioSummary.medianThree("tier-e-benchmark: serialization_ns=1", "serialization_ns")
        }
    }

    @Test
    fun `five native matched forks rotate all five flavours`() {
        val modes = TierENativeScenarioRunner.plan("matched")
        assertEquals(25, modes.size)
        assertEquals("agent_matched_benchmark_none", modes[0])
        assertEquals("agent_matched_benchmark_dictionary_full", modes[5])
    }

    @Test
    fun `release distribution drops three warmups and uses the last ten samples`() {
        val rows = (1..13).joinToString("\n") { "tier-e-benchmark: get_stack_trace_ns=$it" }
        val summary = TierENativeScenarioSummary.release(rows, "get_stack_trace_ns")
        assertEquals(8L, summary.p50)
        assertEquals(13L, summary.p95)
        assertThrows(IllegalArgumentException::class.java) {
            TierENativeScenarioSummary.release("tier-e-benchmark: get_stack_trace_ns=1", "get_stack_trace_ns")
        }
    }
}
