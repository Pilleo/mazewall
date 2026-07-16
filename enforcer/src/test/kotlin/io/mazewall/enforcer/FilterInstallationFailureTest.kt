package io.mazewall.enforcer

import io.mazewall.Policy
import io.mazewall.Platform
import io.mazewall.PlatformProvider
import io.mazewall.RealPlatformProvider
import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import io.mazewall.seccomp.PureJavaBpfEngine
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FilterInstallationFailureTest {

    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
        Platform.resetToDefault()
        ThreadStateRegistry.state = ContainerState()
        System.clearProperty("io.mazewall.fallback")
        PureJavaBpfEngine.clearCache()
    }

    @Test
    fun `test state IS reverted on failure`() {
        val mockPlatform = object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"
            override fun getLandlockAbiVersion(): Int = 5
            override fun hasKernelSeccompSupport(): Boolean = true
            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                LinuxNative.SyscallResult.Error(22, -1)
        }
        Platform.setProvider(mockPlatform)

        val mockEngine = MockNativeEngine()

        // Let's use a non-supervised policy.
        val policy = Policy.builder().allowFsRead("/tmp").build()

        // PureJavaBpfEngine.install calls LinuxNative.raw.syscall(SECCOMP_SET_MODE_FILTER, ...)
        mockEngine.onSyscall = { _, nr, _, _, _, _, _, _ ->
            if (nr == io.mazewall.core.Arch.current().seccompSyscallNumber.toLong()) {
                LinuxNative.SyscallResult.Error(22, -1) // EINVAL
            } else {
                LinuxNative.SyscallResult.Success(0L)
            }
        }

        LinuxNative.setEngine(mockEngine)

        // Initial state
        val initialState = ContainerState()
        ThreadStateRegistry.state = initialState

        assertFailsWith<IllegalStateException> {
            ContainedExecutors.installOnCurrentThread(policy)
        }

        // VERIFY: state WAS reverted
        assertEquals(initialState, ThreadStateRegistry.state)
        assertNull(ThreadStateRegistry.state.landlockPolicy)
    }
}
