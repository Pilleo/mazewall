package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TierETrustworthyGuestCommandTest {
    @Test
    fun `guest benchmark command preserves profile, measured dimensions and output path`() {
        val settings = TierETrustworthySettings.load(
            mapOf(
            "MAZEWALL_TIER_E_RUN_LEVEL" to "smoke",
            "MAZEWALL_TIER_E_BENCHMARK_SCOPE" to "tier-e-coverage-discovery",
            "MAZEWALL_TIER_E_CALIBRATION" to "false",
        ),
        )
        val command = TierETrustworthyGuestCommand.java(settings, 2, 4)
        assertTrue("-Dmazewall.tier-e.benchmark-scope=tier-e-coverage-discovery" in command)
        assertTrue("-Dmazewall.tier-e.calibration=false" in command)
        assertEquals(listOf("100", "3", "3", "1", "2", "4", "/artifacts/results.jsonl"), command.takeLast(7))
        assertEquals("java", command.first())
    }
}
