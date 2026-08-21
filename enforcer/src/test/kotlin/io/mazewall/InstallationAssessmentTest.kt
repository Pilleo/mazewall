package io.mazewall

import io.mazewall.core.Syscall
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallationAssessmentTest {
    @AfterEach
    fun reset() {
        Platform.resetToDefault()
        System.clearProperty("io.mazewall.fallback")
    }

    @Test
    fun `unsupported platform is not installable even under WARN_AND_BYPASS`() {
        val bypassName = Platform.FallbackBehavior.entries.first { it.name.startsWith("WARN") }.name
        System.setProperty("io.mazewall.fallback", bypassName)
        Platform.setProvider(
            MockPlatformProvider().apply {
                mockOsName = "Linux"
                mockKernelSeccompSupport = false
            },
        )

        val assessment =
            InstallationAssessor.assess(
                Policy.NO_EXEC_HOTSPOT.definition,
                processWide = true,
            )

        assertEquals(bypassName, assessment.fallback.name)
        assertFalse(assessment.installable, "fallback must not authorize a bypass")
        assertTrue(assessment.blockedStages.contains(InstallationStage.SECCOMP))
        assertFailsWith<InstallationRejectedException> { assessment.requireInstallable() }
    }

    @Test
    fun `landlock policy is blocked when ABI is missing`() {
        Platform.setProvider(
            MockPlatformProvider().apply {
                mockOsName = "Linux"
                mockKernelSeccompSupport = true
                mockLandlockAbiVersion = 0
            },
        )
        val policy =
            Policy
                .builder()
                .allowFsRead("/tmp")
                .build()
        val assessment = InstallationAssessor.assess(policy.definition, processWide = false)
        assertTrue(assessment.landlockRequired)
        assertFalse(assessment.installable)
        assertTrue(assessment.blockedStages.contains(InstallationStage.LANDLOCK))
    }

    @Test
    fun `process-wide Landlock is blocked without TSYNC`() {
        Platform.setProvider(
            MockPlatformProvider().apply {
                mockOsName = "Linux"
                mockKernelSeccompSupport = true
                mockLandlockAbiVersion = 5
            },
        )
        val policy =
            Policy
                .builder()
                .allowFsRead("/tmp")
                .build()
        val assessment = InstallationAssessor.assess(policy.definition, processWide = true)
        assertTrue(assessment.landlockRequired)
        assertFalse(assessment.installable)
        assertTrue(assessment.blockedStages.contains(InstallationStage.LANDLOCK))
    }

    @Test
    fun `notify policy is blocked without USER_NOTIF`() {
        Platform.setProvider(
            MockPlatformProvider().apply {
                mockOsName = "Linux"
                mockKernelSeccompSupport = true
                mockSeccompUserNotifSupported = false
            },
        )
        val policy =
            Policy
                .builder()
                .addAction(io.mazewall.core.SeccompAction.ACT_NOTIFY, Syscall.OPENAT)
                .build()
        val assessment = InstallationAssessor.assess(policy.definition, processWide = false)
        assertTrue(assessment.userNotifRequired)
        assertFalse(assessment.installable)
        assertTrue(assessment.blockedStages.contains(InstallationStage.USER_NOTIF))
    }

    @Test
    fun `process-wide USER_NOTIF is never installable`() {
        Platform.setProvider(
            MockPlatformProvider().apply {
                mockOsName = "Linux"
                mockKernelSeccompSupport = true
                mockSeccompUserNotifSupported = true
                mockSeccompTsyncSupported = true
            },
        )
        val policy =
            Policy
                .builder()
                .addAction(io.mazewall.core.SeccompAction.ACT_NOTIFY, Syscall.OPENAT)
                .build()
        val assessment = InstallationAssessor.assess(policy.definition, processWide = true)
        assertTrue(assessment.userNotifRequired)
        assertFalse(assessment.installable)
        assertTrue(assessment.blockedStages.contains(InstallationStage.USER_NOTIF))
    }

    @Test
    fun `supported process-wide HOTSPOT baseline is installable and inspectable`() {
        Platform.setProvider(
            MockPlatformProvider().apply {
                mockOsName = "Linux"
                mockKernelSeccompSupport = true
                mockSeccompTsyncSupported = true
            },
        )
        val assessment =
            InstallationAssessor.assess(
                Policy.NO_EXEC_HOTSPOT.definition,
                processWide = true,
            )
        assertTrue(assessment.installable)
        assertTrue(assessment.tsyncRequired)
        assertTrue(assessment.argumentRules.allowExecutableMappings)
        assertEquals(InstallationScope.PROCESS, assessment.scope)
        assessment.requireInstallable()
    }
}
