package io.mazewall.seccomp

import org.junit.jupiter.api.Test
import io.mazewall.ffi.memory.ManagedSegment
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeccompInstallationStateCoverageTest {

    @Test
    fun `installation states define the complete merge strength order`() {
        val states = listOf(
            SeccompInstallationState.Uninitialized,
            SeccompInstallationState.Failed("step", 1, RuntimeException("err")),
            SeccompInstallationState.FilterBuilt(ManagedSegment.NULL),
            SeccompInstallationState.PrivilegesLocked(ManagedSegment.NULL),
            SeccompInstallationState.SystemCallApplied,
            SeccompInstallationState.FallbackPrctlApplied,
            SeccompInstallationState.Verified,
        )

        assertEquals(listOf(0, 1, 2, 3, 4, 4, 5), states.map(SeccompInstallationState::rank))
    }

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
