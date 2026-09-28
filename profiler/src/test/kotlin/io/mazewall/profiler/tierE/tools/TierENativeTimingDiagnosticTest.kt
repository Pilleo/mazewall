package io.mazewall.profiler.tierE.tools

import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.ContainerEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierENativeTimingDiagnosticTest {
    @Test
    fun `fixed schedule contains 36 fresh forks and balances orders`() {
        val samples = TierENativeTimingDiagnostic.schedule()
        assertEquals(36, samples.size)
        assertEquals(listOf('A', 'B', 'C'), samples.take(6).map { it.arm }.distinct())
        assertEquals(listOf('B', 'C', 'A'), samples.subList(6, 12).map { it.arm }.distinct())
        assertEquals(listOf("full", "unique"), samples.take(2).map { it.mode })
        assertEquals(listOf("unique", "full"), samples.subList(6, 8).map { it.mode })
        assertEquals((1..36).toList(), samples.map { it.index })
    }

    @Test
    fun `instrumented evidence requires complete clocks and matching workload`() {
        val sample =
            sampleLog(
                "capture-timing-diagnostic: attempted=514 completed=514 invalid=0 elapsed_ns=1234 cpu_ns=800 migrations=0 voluntary_switches=0 involuntary_switches=0 clock_pair_ns=50 clock_pair_valid=1",
            )
        val parsed = TierENativeTimingDiagnostic.parse('C', "unique", sample)
        assertEquals(1234L, parsed.captureElapsedNs)
        assertEquals(800L, parsed.captureCpuNs)
        assertEquals(514L, parsed.captures)
        assertTrue(!parsed.policyEligibility)
        assertThrows(IllegalArgumentException::class.java) {
            TierENativeTimingDiagnostic.parse('C', "unique", sample.replace("invalid=0", "invalid=1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TierENativeTimingDiagnostic.parse('C', "unique", sample.replace("stack-captures=514", "stack-captures=513"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TierENativeTimingDiagnostic.parse('C', "unique", sample.replace("clock_pair_valid=1", "clock_pair_valid=0"))
        }
    }

    @Test
    fun `controls reject instrumented data and require matching emission mode`() {
        assertThrows(IllegalArgumentException::class.java) {
            TierENativeTimingDiagnostic.parse(
                'A',
                "unique",
                sampleLog(
                    "capture-timing-diagnostic: attempted=514 completed=514 invalid=0 elapsed_ns=1234 cpu_ns=800 migrations=0 voluntary_switches=0 involuntary_switches=0 clock_pair_ns=50 clock_pair_valid=1",
                ),
            )
        }
        val control = TierENativeTimingDiagnostic.parse('B', "unique", sampleLog(""))
        assertEquals(null, control.captureCpuNs)
        assertThrows(IllegalArgumentException::class.java) {
            TierENativeTimingDiagnostic.parse('B', "full", sampleLog(""))
        }
    }

    @Test
    fun `remote endpoint cannot allocate diagnostic resources or output`() {
        val root = Files.createTempDirectory("tier-e-timing-root-")
        val output = root.resolve("fresh")
        try {
            assertThrows(IllegalArgumentException::class.java) {
                TierENativeTimingDiagnosticRunner.run(root, output, ContainerEndpoint(ContainerEngine.DOCKER, "ssh://remote"))
            }
            assertTrue(Files.notExists(output))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun sampleLog(diagnostic: String) =
        """
        agent-workload: workloadNs=70000000
        mazewall-stack-agent: native-binds=248 proxied-binds=4 stack-captures=514 stack-failures=0
        tier-e-benchmark: get_stack_trace_ns=3157189 raw_key_ns=123 canonical_cache_lookup_ns=456 logical_metadata_ns=789 emission_mode=unique-stack-syscall cache_hits=504 cache_misses=10
        $diagnostic
    """.trimIndent()
}
