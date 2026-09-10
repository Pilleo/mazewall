package demo

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import io.mazewall.IsolatedProcessTester
import kotlin.test.assertTrue

class ProfileAndEnforceDemoTest {
    @Test
    @EnabledOnOs(OS.LINUX)
    fun `test profile and enforce demo runs successfully`() {
        if (!io.mazewall.Platform.isSupported()) return

        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "profileBody")
    }

    @Suppress("unused")
    fun profileBody() {
        val report = runProfileAndEnforce()
        assertTrue(report.passed, report.summary())
    }
}
