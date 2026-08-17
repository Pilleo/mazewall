package io.mazewall

import io.mazewall.enforcer.api.ContainedExecutors
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class InstallationAssessmentIntegrationTest : BaseIntegrationTest() {
    @Test
    fun `assess is stable and does not require a fresh JVM`() {
        val first = ContainedExecutors.assessOnProcess(Policy.NO_EXEC_HOTSPOT)
        val second = ContainedExecutors.assessOnProcess(Policy.NO_EXEC_HOTSPOT)
        assertEquals(first.installable, second.installable)
        assertEquals(first.tsyncRequired, second.tsyncRequired)
        assertFalse(first.virtualThread)
        if (first.installable) {
            first.requireInstallable()
        }
    }
}
