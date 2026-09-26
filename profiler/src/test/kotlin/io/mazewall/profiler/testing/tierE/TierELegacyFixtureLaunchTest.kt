package io.mazewall.profiler.testing.tierE

import io.mazewall.profiler.tierE.tools.TierELegacyFixtureLaunch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TierELegacyFixtureLaunchTest {
    @Test
    fun `legacy coverage environment becomes a typed fixture request`() {
        val launch = TierELegacyFixtureLaunch.translate(
            mapOf(
                "TIER_E_SPLIT_COVERAGE" to "1",
                "TIER_E_SPLIT_MARKER_VERSION" to "v2",
                "TIER_E_SPLIT_RECOVERY_MODE" to "recover",
                "TIER_E_SPLIT_SOCKET_ACCESS" to "owner",
                "TIER_E_COVERAGE_SEED" to "23070",
                "TIER_E_SPLIT_WORKLOAD_CPU" to "2",
                "TIER_E_SPLIT_COLLECTOR_CPU" to "3",
            ),
        )
        assertEquals("v2", launch.configuration.scenario.markerVersion)
        assertEquals("owner-only", launch.configuration.scenario.socketAccess)
        assertEquals(23070, launch.seed)
        assertTrue(launch.pinCpus)
        assertEquals(2, launch.configuration.measurement.workloadCpu)
    }

    @Test
    fun `live snapshots and benchmark phases are explicit fixture inputs`() {
        val launch = TierELegacyFixtureLaunch.translate(
            mapOf(
                "TIER_E_SPLIT_BENCHMARK" to "1",
                "TIER_E_SPLIT_LIVE_DEFINITIONS" to "1",
                "TIER_E_SPLIT_ADMIT_LIVE_SNAPSHOTS" to "1",
                "TIER_E_SPLIT_BENCHMARK_PHASES" to "1",
                "TIER_E_SPLIT_WORKLOAD_PROFILE" to "class_churn",
            ),
        )
        assertTrue(launch.configuration.scenario.liveSnapshots)
        assertTrue(launch.benchmarkPhases)
        assertEquals("class_churn", launch.workloadProfile)
    }

    @Test
    fun `real guest reuse and incomplete coverage stay outside the launcher`() {
        assertThrows(IllegalArgumentException::class.java) {
            TierELegacyFixtureLaunch.translate(mapOf("TIER_E_SPLIT_REAL_PID_REUSE" to "1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TierELegacyFixtureLaunch.translate(mapOf("TIER_E_SPLIT_ALLOW_INCOMPLETE" to "1", "TIER_E_SPLIT_COVERAGE" to "1"))
        }
    }
}
