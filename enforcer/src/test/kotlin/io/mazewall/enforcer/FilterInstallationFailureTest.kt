package io.mazewall.enforcer

import io.mazewall.Policy
import io.mazewall.Platform
import io.mazewall.PlatformProvider
import io.mazewall.RealPlatformProvider
import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FilterInstallationFailureTest {

    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
        Platform.resetToDefault()
        ContainmentStateRegistry.threadState = ContainerState()
    }

    @Test
    fun `test state IS reverted on failure when Landlock is not applied`() {
        val mockPlatform = object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"
            override fun getLandlockAbiVersion(): Int = 5
            override fun hasKernelSeccompSupport(): Boolean = true
            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                LinuxNative.SyscallResult.Error(22, -1)
        }
        Platform.setProvider(mockPlatform)

        val mockEngine = MockNativeEngine()

        // Policy WITHOUT filesystem paths — Landlock will NOT be applied
        val policy = Policy.builder().block(Syscall.EXECVE).build()

        // PureJavaBpfEngine.install calls LinuxNative.raw.syscall(SECCOMP_SET_MODE_FILTER, ...)
        mockEngine.onSyscall = { nr, _, _, _, _, _, _ ->
            if (nr == io.mazewall.core.Arch.current().seccompSyscallNumber.toLong()) {
                LinuxNative.SyscallResult.Error(22, -1) // EINVAL
            } else {
                LinuxNative.SyscallResult.Success(42L)
            }
        }

        LinuxNative.setEngine(mockEngine)

        // Initial state
        val initialState = ContainerState()
        ContainmentStateRegistry.threadState = initialState

        assertFailsWith<IllegalStateException> {
            ContainedExecutors.installOnCurrentThread(policy)
        }

        // VERIFY: state WAS reverted because Landlock was not applied
        assertEquals(initialState, ContainmentStateRegistry.threadState)
        assertNull(ContainmentStateRegistry.threadState.landlockPolicy)
    }

    @Test
    fun `test state is NOT reverted on failure when Landlock IS applied`() {
        val mockPlatform = object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"
            override fun getLandlockAbiVersion(): Int = 5
            override fun hasKernelSeccompSupport(): Boolean = true
            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                LinuxNative.SyscallResult.Error(22, -1)
        }
        Platform.setProvider(mockPlatform)

        val mockEngine = MockNativeEngine()

        // Policy WITH filesystem paths — Landlock WILL be applied (and is irreversible)
        val policy = Policy.builder().allowFsRead("/tmp").build()

        // PureJavaBpfEngine.install calls LinuxNative.raw.syscall(SECCOMP_SET_MODE_FILTER, ...)
        mockEngine.onSyscall = { nr, _, _, _, _, _, _ ->
            if (nr == io.mazewall.core.Arch.current().seccompSyscallNumber.toLong()) {
                LinuxNative.SyscallResult.Error(22, -1) // EINVAL
            } else {
                LinuxNative.SyscallResult.Success(42L)
            }
        }

        LinuxNative.setEngine(mockEngine)

        // Initial state
        val initialState = ContainerState()
        ContainmentStateRegistry.threadState = initialState

        assertFailsWith<IllegalStateException> {
            ContainedExecutors.installOnCurrentThread(policy)
        }

        // VERIFY: state was NOT reverted because Landlock was applied and is irreversible
        assertNotNull(ContainmentStateRegistry.threadState.landlockPolicy)
    }
}
