package demo

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import io.mazewall.IsolatedProcessTester
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileAndEnforceDemoTest {
    @Test
    @EnabledOnOs(OS.LINUX)
    fun `profile demo does not claim success from inferred containment`() {
        if (!io.mazewall.Platform.isSupported()) return

        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "profileBody")
    }

    @Suppress("unused")
    fun profileBody() {
        val report = runProfileAndEnforce()
        assertFalse(report.passed, report.summary())
        assertTrue(report.summary().contains("INFRASTRUCTURE FAILURE"), report.summary())
    }
}
