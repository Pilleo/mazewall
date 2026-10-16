package io.mazewall.enforcer.api

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import kotlin.test.*

@Isolated
class PlatformProviderTest {
    @AfterEach
    fun tearDown() {
        Platform.resetToDefault()
    }

    @Test
    fun `isLinux returns true when provider says so`() {
        val mock = MockPlatformProvider()
        mock.mockOsName = "Linux"
        Platform.setProvider(mock)
        assertTrue(Platform.isLinux)
    }

    @Test
    fun `isLinux returns false for Windows`() {
        val mock = MockPlatformProvider()
        mock.mockOsName = "Windows 11"
        Platform.setProvider(mock)
        assertFalse(Platform.isLinux)
    }

    @Test
    fun `diagnose uses provider values`() {
        val mock = MockPlatformProvider()
        mock.mockOsName = "MockOS"
        mock.mockOsVersion = "1.2.3"
        mock.mockOsArch = "mock-arch"
        mock.mockNoNewPrivsEnabled = true
        mock.mockSeccompMode = SeccompMode.Filter
        mock.mockYamaPtraceScope = YamaPtraceScope.AdminOnly
        mock.mockLandlockAbiVersion = 4
        mock.mockContainer = true

        Platform.setProvider(mock)
        val d = Platform.diagnose()

        assertEquals("MockOS", d.osName)
        assertEquals("1.2.3", d.osVersion)
        assertEquals("mock-arch", d.osArch)
        assertEquals(true, d.isNoNewPrivsEnabled)
        assertEquals(SeccompMode.Filter, d.seccompMode)
        assertEquals(YamaPtraceScope.AdminOnly, d.yamaPtraceScope)
        assertEquals(4, d.features.landlockAbiVersion)
        assertEquals(true, d.isContainer)
        assertFalse(d.isLinux)
    }

    @Test
    fun `isSupported returns false if sanity check fails`() {
        val mock = MockPlatformProvider()
        mock.mockOsName = "Linux"
        mock.mockKernelSeccompSupport = true
        // Sanity check fails (returns success instead of expected EINVAL)
        mock.mockSeccompSanityCheckResult = LinuxNative.SyscallResult.Success(0L)

        Platform.setProvider(mock)
        assertFalse(Platform.isSupported())
    }

    @Test
    fun `isSupported returns false if no kernel support`() {
        val mock = MockPlatformProvider()
        mock.mockOsName = "Linux"
        mock.mockKernelSeccompSupport = false

        Platform.setProvider(mock)
        assertFalse(Platform.isSupported())
    }

    @Test
    fun `featureMatrix resolves correctly from provider`() {
        val mock = MockPlatformProvider()
        mock.mockKernelSeccompSupport = true
        mock.mockSeccompTsyncSupported = true
        mock.mockSeccompUserNotifSupported = false
        mock.mockLandlockAbiVersion = 5

        Platform.setProvider(mock)
        val matrix = KernelFeatureMatrix.resolve(mock)

        assertTrue(matrix.seccompSupported)
        assertTrue(matrix.seccompTsyncSupported)
        assertFalse(matrix.seccompUserNotifSupported)
        assertEquals(5, matrix.landlockAbiVersion)
        assertTrue(matrix.landlockSupported)
        assertFalse(matrix.landlockTsyncSupported) // Needs ABI 8
    }

    @Test
    fun `legacy provider receives typed Landlock capability by default`() {
        val legacyProvider = MockPlatformProvider().apply { mockLandlockAbiVersion = 7 }

        assertEquals(KernelCapability.Available(7), legacyProvider.getLandlockCapability())

        legacyProvider.mockLandlockAbiVersion = 0
        assertEquals(KernelCapability.Unsupported, legacyProvider.getLandlockCapability())
    }

    @Test
    fun `assessment uses cached typed capability instead of reconstructing it from ABI zero`() {
        val provider =
            object : MockPlatformProvider() {
                override fun getLandlockCapability(): KernelCapability<Int> = KernelCapability.Rejected(io.mazewall.ffi.NativeConstants.EPERM)
            }
        Platform.setProvider(provider)

        assertEquals(0, Platform.featureMatrix.landlockAbiVersion)

        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).allowFsRead("/tmp").build()
        val assessment = InstallationAssessor.assess(policy.definition, processWide = false)

        assertContains(assessment.blockingReasons, "landlock capability rejected with errno 1")
    }
}
