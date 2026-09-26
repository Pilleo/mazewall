package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TierENodeNoiseSampleTest {
    @Test
    fun `noise observation requires exact syscall count and user system and elapsed time`() {
        val sample = TierENodeNoiseSample.parse("syscall_loop_iterations=20\nuser_seconds=0.02 system_seconds=0.03 elapsed_seconds=0.05\n")
        assertEquals(20L, sample.iterations)
        assertThrows(IllegalArgumentException::class.java) { TierENodeNoiseSample.parse("syscall_loop_iterations=20\n") }
    }
}
