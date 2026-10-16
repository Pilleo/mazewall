package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TierEAllowedCpuVerifierTest {
    @Test
    fun `rejects unavailable or repeated measured CPUs before a runner is created`() {
        assertDoesNotThrow { TierEAllowedCpuVerifier.requireAvailable("0,1,2", "0-3,8") }
        assertThrows(IllegalArgumentException::class.java) { TierEAllowedCpuVerifier.requireAvailable("0,4", "0-3,8") }
        assertThrows(IllegalArgumentException::class.java) { TierEAllowedCpuVerifier.requireAvailable("0,0", "0-3") }
        assertThrows(IllegalArgumentException::class.java) { TierEAllowedCpuVerifier.requireAvailable("1", "0,2-3") }
        assertThrows(IllegalArgumentException::class.java) { TierEAllowedCpuVerifier.requireAvailable("0", "3-2") }
    }
}
