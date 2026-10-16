package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TierESustainedCapacitySettingsTest {
    @Test
    fun `stage planning derives bounded operations and rejects nondivisible rates`() {
        val config = TierESustainedCapacitySettings.load(mapOf("MAZEWALL_TIER_E_SUSTAINED_PILOT_RATES" to "1000 2000"))
        assertEquals(listOf(1000, 2000), config.pilotRates)
        val stage = config.plan(1000, 10, 1, "pilot")
        assertEquals(50, stage.batches)
        assertEquals(31_800L, stage.maxRetainedRecords)
        assertThrows(IllegalArgumentException::class.java) { config.plan(99, 1, 1, "pilot") }
        assertThrows(IllegalArgumentException::class.java) {
            TierESustainedCapacitySettings.load(mapOf("MAZEWALL_TIER_E_SUSTAINED_PILOT_RATES" to "1000 bogus"))
        }
    }
}
