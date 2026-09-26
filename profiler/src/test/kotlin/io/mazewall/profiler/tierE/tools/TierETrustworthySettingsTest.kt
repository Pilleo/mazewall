package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TierETrustworthySettingsTest {
    @Test
    fun `smoke level is bounded and decision level cannot use fewer than fifteen forks`() {
        val smoke = TierETrustworthySettings.load(mapOf("MAZEWALL_TIER_E_RUN_LEVEL" to "smoke"))
        assertEquals(1, smoke.forks)
        assertEquals(3, smoke.batches)
        assertEquals("full", smoke.scope)
        assertThrows(IllegalArgumentException::class.java) {
            TierETrustworthySettings.load(mapOf("MAZEWALL_TIER_E_RUN_LEVEL" to "decision", "MAZEWALL_TIER_E_FORKS" to "1"))
        }
    }

    @Test
    fun `malformed flags and conflicting profilers fail before any container creation`() {
        for (environment in listOf(
            mapOf("MAZEWALL_TIER_E_PERF_RECORD" to "yes"),
            mapOf("MAZEWALL_TIER_E_PERF_RECORD" to "true", "MAZEWALL_TIER_E_ALLOCATION_RECORD" to "true"),
            mapOf("MAZEWALL_TIER_E_BENCHMARK_SCOPE" to "unexpected"),
            mapOf("MAZEWALL_TIER_E_USER_NOTIF_CHILD_TIMEOUT_SECONDS" to "480"),
        )) {
            assertThrows(IllegalArgumentException::class.java) { TierETrustworthySettings.load(environment) }
        }
    }
}
