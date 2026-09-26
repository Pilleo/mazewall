package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TierECoverageExperimentTest {
    @Test
    fun `smoke and full presets pin the same coverage workload with different bounded dimensions`() {
        val smoke = TierECoverageExperiment.settings("smoke")
        val full = TierECoverageExperiment.settings("full")
        assertEquals(24, smoke.batches)
        assertEquals(1, smoke.forks)
        assertEquals(750, full.batches)
        assertEquals(5, full.forks)
        assertEquals("tier-e-coverage-discovery", smoke.scope)
        assertEquals("oracle", full.workload)
        assertThrows(IllegalArgumentException::class.java) { TierECoverageExperiment.settings("unknown") }
    }
}
