package io.mazewall.enforcer

import io.mazewall.LinuxNative
import io.mazewall.Platform
import io.mazewall.PlatformProvider
import io.mazewall.SeccompMode
import io.mazewall.YamaPtraceScope
import io.mazewall.LinuxNative.SyscallResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class ValidationCoverageTest {

    @AfterEach
    fun tearDown() {
        Platform.resetToDefault()
        LinuxNative.resetToDefault()
        System.clearProperty("io.mazewall.fallback")
    }

    @Test
    fun `test validateLinuxAndNotVirtual throws on non-Linux`() {
        val mockProvider = object : PlatformProvider {
            override fun getOsName(): String = "macOS"
            override fun getOsVersion(): String = "14.0"
            override fun getOsArch(): String = "aarch64"
            override fun hasKernelSeccompSupport(): Boolean = false
            override fun getSeccompMode(): SeccompMode = SeccompMode.Disabled
            override fun checkSeccompSanity(): SyscallResult<Long, io.mazewall.LinuxNative.SyscallHandledState.Unhandled> = SyscallResult.Error(38, -1)
            override fun isNoNewPrivsEnabled(): Boolean = false
            override fun getYamaPtraceScope(): YamaPtraceScope = YamaPtraceScope.Unavailable
            override fun getLandlockAbiVersion(): Int = 0
            override fun probeSeccompTsync(): Boolean = false
            override fun probeSeccompUserNotif(): Boolean = false
            override fun isContainer(): Boolean = false
        }

        Platform.setProvider(mockProvider)
        assertFailsWith<UnsupportedOperationException> {
            validateLinuxAndNotVirtual()
        }
    }
}
