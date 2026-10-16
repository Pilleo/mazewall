package io.mazewall.profiler.tierE.stress

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TierESustainedComparisonModeTest {
    @Test
    fun `original target-authored FD denominator is retained regardless of paired audit`() {
        assertTrue(TierESustainedComparisonMode.exportDeclaredManifest(comparison = true, sustainedSharedBenchmark = true, pairedAudit = false))
        assertTrue(TierESustainedComparisonMode.exportDeclaredManifest(comparison = true, sustainedSharedBenchmark = true, pairedAudit = true))
        assertTrue(TierESustainedComparisonMode.exportDeclaredManifest(comparison = false, sustainedSharedBenchmark = false, pairedAudit = true))
        assertFalse(TierESustainedComparisonMode.exportDeclaredManifest(comparison = false, sustainedSharedBenchmark = true, pairedAudit = false))
    }

    @Test
    fun `comparison cannot claim source-bound manifest outside sustained shared benchmark`() {
        assertThrows(IllegalStateException::class.java) {
            TierESustainedComparisonMode.exportDeclaredManifest(comparison = true, sustainedSharedBenchmark = false, pairedAudit = false)
        }
    }

    @Test
    fun `comparison chooses exactly audit-disabled or paired witness without changing other scopes`() {
        assertTrue(TierESustainedComparisonMode.select("tier-e-sustained-collector", false, false, "false", "true").comparison)
        assertTrue(TierESustainedComparisonMode.select("tier-e-sustained-collector", true, true, "true", "true").invocationWitness)
        assertThrows(IllegalArgumentException::class.java) {
            TierESustainedComparisonMode.select("tier-e-sustained-collector", true, false, "false", "true")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TierESustainedComparisonMode.select("tier-e-sustained-collector", true, true, "false", "true")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TierESustainedComparisonMode.select("tier-e-calibration", false, false, "false", "true")
        }
    }
}
