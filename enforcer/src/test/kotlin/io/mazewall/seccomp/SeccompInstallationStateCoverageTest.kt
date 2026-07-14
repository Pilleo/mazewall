package io.mazewall.seccomp

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeccompInstallationStateCoverageTest {

    @Test
    fun `test SeccompInstallationState toString and properties`() {
        val uninitialized = SeccompInstallationState.Uninitialized
        assertTrue(uninitialized.toString().contains("Uninitialized"))

        val failed = SeccompInstallationState.Failed("step", 1, RuntimeException("err"))
        assertTrue(failed.toString().contains("Failed"))
        assertEquals("step", failed.step)
        assertEquals(1, failed.errno)
        assertEquals("err", failed.error.message)

        val verified = SeccompInstallationState.Verified
        assertTrue(verified.toString().contains("Verified"))

        val applied = SeccompInstallationState.SystemCallApplied
        assertTrue(applied.toString().contains("SystemCallApplied"))

        val fallback = SeccompInstallationState.FallbackPrctlApplied
        assertTrue(fallback.toString().contains("FallbackPrctlApplied"))
    }
}
