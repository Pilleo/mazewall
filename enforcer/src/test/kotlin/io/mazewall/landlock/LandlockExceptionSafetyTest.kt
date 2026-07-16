package io.mazewall.landlock

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.NativeTransaction
import io.mazewall.Policy
import io.mazewall.RealNativeEngine
import io.mazewall.Platform
import io.mazewall.PlatformProvider
import io.mazewall.SeccompMode
import io.mazewall.YamaPtraceScope
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class LandlockExceptionSafetyTest {

    object MockPlatformProvider : PlatformProvider {
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
        override fun isContainer(): Boolean = false
    }

    @Test
    fun `testLandlockSessionFailedStateWithThrowable`() {
        Platform.setProvider(MockPlatformProvider)
        val mockEngine = object : MockNativeEngine() {
            context(context: NativeTransaction)
            override fun syscall(
                nr: Long,
                a1: io.mazewall.core.NativeArg,
                a2: io.mazewall.core.NativeArg,
                a3: io.mazewall.core.NativeArg,
                a4: io.mazewall.core.NativeArg,
                a5: io.mazewall.core.NativeArg,
                a6: io.mazewall.core.NativeArg,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                // Throw an Error (not an Exception) to see if it's caught
                throw NoSuchMethodError("Simulated native error")
            }
        }
        LinuxNative.setEngine(mockEngine)
        try {
            val session = LandlockSession(Policy.PURE_COMPUTE_UNSAFE.definition)
            assertFailsWith<NoSuchMethodError> {
                session.applyRuleset()
            }
            // If the bug is present, state will NOT be Failed
            assertTrue(session.state is LandlockState.Failed, "State should be Failed, but was ${session.state}")
        } finally {
            LinuxNative.setEngine(RealNativeEngine)
            Platform.resetToDefault()
        }
    }
}
