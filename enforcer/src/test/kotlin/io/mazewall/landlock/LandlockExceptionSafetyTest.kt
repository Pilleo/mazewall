package io.mazewall.landlock

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.api.Platform
import io.mazewall.enforcer.api.PlatformProvider
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.SeccompMode
import io.mazewall.enforcer.api.YamaPtraceScope
import io.mazewall.ffi.internal.RealNativeEngine
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LandlockExceptionSafetyTest {
    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
        Platform.resetToDefault()
    }

    internal object MockPlatformProvider : PlatformProvider {
        override fun getOsName(): String = "Linux"

        override fun getOsVersion(): String = "5.15.0"

        override fun getOsArch(): String = "amd64"

        override fun hasKernelSeccompSupport(): Boolean = true

        override fun getSeccompMode(): SeccompMode = SeccompMode.Disabled

        override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Error(22, -1)

        override fun isNoNewPrivsEnabled(): Boolean = false

        override fun getYamaPtraceScope(): YamaPtraceScope = YamaPtraceScope.Classic

        override fun getLandlockAbiVersion(): Int = 5

        override fun probeSeccompTsync(): Boolean = true

        override fun probeSeccompUserNotif(): Boolean = true

        override fun probeCetSupported(): Boolean = false

        override fun isContainer(): Boolean = false
    }

    @Test
    fun `testLandlockSessionFailedStateWithThrowable`() {
        Platform.setProvider(MockPlatformProvider)
        val mockEngine = object : MockNativeEngine() {
            override fun syscall(
                nr: Long,
                a1: io.mazewall.platform.syscall.NativeArg,
                a2: io.mazewall.platform.syscall.NativeArg,
                a3: io.mazewall.platform.syscall.NativeArg,
                a4: io.mazewall.platform.syscall.NativeArg,
                a5: io.mazewall.platform.syscall.NativeArg,
                a6: io.mazewall.platform.syscall.NativeArg,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                // Throw an Error (not an Exception) to see if it's caught
                throw NoSuchMethodError("Simulated native error")
            }
        }
        LinuxNative.setEngine(mockEngine)

        val session = LandlockSession(Policy.PURE_COMPUTE_UNSAFE.definition)
        assertFailsWith<NoSuchMethodError> {
            session.applyRuleset()
        }
        // If the bug is present, state will NOT be Failed
        assertTrue(session.state is LandlockState.Failed, "State should be Failed, but was ${session.state}")
    }
}
