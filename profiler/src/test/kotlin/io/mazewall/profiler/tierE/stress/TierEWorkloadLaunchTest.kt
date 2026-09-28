package io.mazewall.profiler.tierE.stress

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TierEWorkloadLaunchTest {
    @Test
    fun `audited target starts on requested CPU before JVM creates worker threads`() {
        assertEquals(
            listOf("taskset", "-c", "0", "java", "-cp", "/bundle/runtime/*", "Target"),
            TierEWorkloadLaunch.command(listOf("java", "-cp", "/bundle/runtime/*", "Target"), "0", startupPin = true),
        )
    }

    @Test
    fun `legacy unaudited launch and existing coverage target policy remain explicit`() {
        val java = listOf("java", "Target")
        assertEquals(java, TierEWorkloadLaunch.command(java, "0", startupPin = false))
        assertEquals(listOf("taskset", "-c", "0", "java", "Target"), TierEWorkloadLaunch.command(java, "0", startupPin = true))
        assertThrows(IllegalArgumentException::class.java) { TierEWorkloadLaunch.command(java, "-1", startupPin = true) }
    }

    @Test
    fun `startup audit refuses even one existing JVM thread outside selected workload CPU`() {
        TierEWorkloadLaunch.requireTargetThreadAffinity(mapOf(100 to "0", 101 to "0"), selectedCpu = 0)
        assertThrows(IllegalStateException::class.java) {
            TierEWorkloadLaunch.requireTargetThreadAffinity(mapOf(100 to "0", 101 to "1"), selectedCpu = 0)
        }
        assertThrows(IllegalStateException::class.java) { TierEWorkloadLaunch.requireTargetThreadAffinity(emptyMap(), selectedCpu = 0) }
    }
}
