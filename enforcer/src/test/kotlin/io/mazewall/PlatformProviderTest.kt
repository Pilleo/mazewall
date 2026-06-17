package io.mazewall

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
}
