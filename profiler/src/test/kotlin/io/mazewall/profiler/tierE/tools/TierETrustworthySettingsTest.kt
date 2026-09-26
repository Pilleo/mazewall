package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TierETrustworthySettingsTest {
    @Test
    fun `sustained audit request remains an explicit benchmark-only guest option`() {
        val settings = TierETrustworthySettings.load(
            mapOf(
            "MAZEWALL_TIER_E_BENCHMARK_SCOPE" to "tier-e-sustained-collector",
            "MAZEWALL_TIER_E_SUSTAINED_RATE_PER_SECOND" to "1000",
            "MAZEWALL_TIER_E_BENCHMARK_RAW_SYS_ENTER_AUDIT" to "true",
        ),
        )
        assertTrue(settings.rawSysEnterAudit)
        assertTrue(
            "-Dmazewall.tier-e.benchmark-raw-sys-enter-audit=true" in
            TierETrustworthyGuestCommand.java(settings, 0, 2),
        )
        val exact = TierETrustworthySettings.load(
            mapOf(
            "MAZEWALL_TIER_E_BENCHMARK_SCOPE" to "tier-e-sustained-collector",
            "MAZEWALL_TIER_E_SUSTAINED_RATE_PER_SECOND" to "1000",
            "MAZEWALL_TIER_E_BENCHMARK_EXACT_ADMISSION_AUDIT" to "true",
        ),
        )
        assertTrue(exact.exactAdmissionAudit)
        assertTrue("-Dmazewall.tier-e.benchmark-exact-admission-audit=true" in TierETrustworthyGuestCommand.java(exact, 0, 2))
        assertThrows(IllegalArgumentException::class.java) {
            TierETrustworthySettings.load(mapOf("MAZEWALL_TIER_E_BENCHMARK_RAW_SYS_ENTER_AUDIT" to "maybe"))
        }
    }

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
