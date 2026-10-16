package io.mazewall.enforcer

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.Platform
import io.mazewall.enforcer.api.PlatformProvider
import io.mazewall.enforcer.api.SeccompMode
import io.mazewall.enforcer.api.YamaPtraceScope
import io.mazewall.enforcer.diagnostics.validateLinuxAndNotVirtual
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative.SyscallResult
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class PlatformValidationTest {
    @AfterEach
    fun tearDown() {
        Platform.resetToDefault()
    }

    @Test
    fun `test validateLinuxAndNotVirtual throws on non-Linux`() {
        val mockProvider = object : PlatformProvider {
            override fun getOsName(): String = "macOS"

            override fun getOsVersion(): String = "14.0"

            override fun getOsArch(): String = "aarch64"

            override fun hasKernelSeccompSupport(): Boolean = false

            override fun getSeccompMode(): SeccompMode = SeccompMode.Disabled

            override fun checkSeccompSanity(): SyscallResult<Long, io.mazewall.platform.native.LinuxNative.SyscallHandledState.Unhandled> = SyscallResult.Error(38, -1)

            override fun isNoNewPrivsEnabled(): Boolean = false

            override fun getYamaPtraceScope(): YamaPtraceScope = YamaPtraceScope.Unavailable

            override fun getLandlockAbiVersion(): Int = 0

            override fun probeSeccompTsync(): Boolean = false

            override fun probeSeccompUserNotif(): Boolean = false

            override fun probeCetSupported(): Boolean = false

            override fun isContainer(): Boolean = false
        }

        Platform.setProvider(mockProvider)
        assertFailsWith<UnsupportedOperationException> {
            validateLinuxAndNotVirtual()
        }
    }
}
