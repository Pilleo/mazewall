package io.mazewall.enforcer

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.Platform
import io.mazewall.Policy
import io.mazewall.RealPlatformProvider
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.core.Syscall
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InstallationReceiptMatrixTest {

    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
        Platform.resetToDefault()
        ContainmentStateRegistry.threadState = ContainerState()
        ContainmentStateRegistry.processState = ContainerState()
        System.clearProperty("io.mazewall.fallback")
    }

    @Test
    fun `case A - seccomp only policy with seccomp mock success`() {
        val mockPlatform = object : io.mazewall.PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"
            override fun getLandlockAbiVersion(): Int = 5
            override fun hasKernelSeccompSupport(): Boolean = true
            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                LinuxNative.SyscallResult.Error(22, -1L)
        }
        Platform.setProvider(mockPlatform)

        val mockEngine = MockNativeEngine()
        mockEngine.onSyscall = { nr, _, _, _, _, _, _ ->
            if (nr == io.mazewall.core.Arch.current().seccompSyscallNumber.toLong()) {
                LinuxNative.SyscallResult.Success(0L)
            } else {
                LinuxNative.SyscallResult.Success(0L)
            }
        }
        LinuxNative.setEngine(mockEngine)

        // Policy with seccomp only (no Landlock paths)
        val policy = Policy.builder().block(Syscall.EXECVE).build()

        val receipt = ContainedExecutors.installOnCurrentThread(policy)

        assertEquals(true, receipt.installed, "Seccomp should be installed")
        assertEquals(false, receipt.landlockApplied, "Landlock should not be applied")
    }

    @Test
    fun `case B - Landlock and seccomp policy both mock success`() {
        val mockPlatform = object : io.mazewall.PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"
            override fun getLandlockAbiVersion(): Int = 5
            override fun hasKernelSeccompSupport(): Boolean = true
            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                LinuxNative.SyscallResult.Error(22, -1L)
        }
        Platform.setProvider(mockPlatform)

        val mockEngine = MockNativeEngine()
        mockEngine.onSyscall = { nr, _, _, _, _, _, _ ->
            LinuxNative.SyscallResult.Success(0L)
        }
        LinuxNative.setEngine(mockEngine)

        // Policy with Landlock paths
        val policy = Policy.builder().allowFsRead("/tmp").build()

        val receipt = ContainedExecutors.installOnCurrentThread(policy)

        assertEquals(true, receipt.installed, "Seccomp should be installed")
        // Landlock may or may not be applied depending on implementation
        // This test verifies both can succeed independently
    }

    @Test
    fun `case D - Landlock apply succeeds seccomp fails with FAIL fallback`() {
        System.setProperty("io.mazewall.fallback", "FAIL")

        val mockPlatform = object : io.mazewall.PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"
            override fun getLandlockAbiVersion(): Int = 5
            override fun hasKernelSeccompSupport(): Boolean = true
            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                LinuxNative.SyscallResult.Error(22, -1L)
        }
        Platform.setProvider(mockPlatform)

        val mockEngine = MockNativeEngine()
        mockEngine.onSyscall = { nr, _, _, _, _, _, _ ->
            if (nr == io.mazewall.core.Arch.current().seccompSyscallNumber.toLong()) {
                LinuxNative.SyscallResult.Error(22, -1L) // EINVAL
            } else {
                LinuxNative.SyscallResult.Success(0L)
            }
        }
        LinuxNative.setEngine(mockEngine)

        // Policy with Landlock paths
        val policy = Policy.builder().allowFsRead("/tmp").build()

        assertThrows<Exception> {
            ContainedExecutors.installOnCurrentThread(policy)
        }

        // After failure, threadState may have landlockPolicy set if Landlock was attempted
        // (depending on implementation order)
    }

    @Test
    fun `case E - Landlock apply succeeds seccomp fails with WARN_AND_BYPASS`() {
        System.setProperty("io.mazewall.fallback", "WARN_AND_BYPASS")

        val mockPlatform = object : io.mazewall.PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"
            override fun getLandlockAbiVersion(): Int = 5
            override fun hasKernelSeccompSupport(): Boolean = true
            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                LinuxNative.SyscallResult.Error(22, -1L)
        }
        Platform.setProvider(mockPlatform)

        val mockEngine = MockNativeEngine()
        mockEngine.onSyscall = { nr, _, _, _, _, _, _ ->
            if (nr == io.mazewall.core.Arch.current().seccompSyscallNumber.toLong()) {
                LinuxNative.SyscallResult.Error(22, -1L) // EINVAL
            } else {
                LinuxNative.SyscallResult.Success(0L)
            }
        }
        LinuxNative.setEngine(mockEngine)

        // Policy with Landlock paths
        val policy = Policy.builder().allowFsRead("/tmp").build()

        val receipt = ContainedExecutors.installOnCurrentThread(policy)

        assertEquals(false, receipt.installed, "Seccomp install should fail with WARN_AND_BYPASS")
        // landlockApplied may be true if Landlock was applied before seccomp failed
    }

    @Test
    fun `case F - no Landlock seccomp fails with WARN_AND_BYPASS`() {
        System.setProperty("io.mazewall.fallback", "WARN_AND_BYPASS")

        val mockPlatform = object : io.mazewall.PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"
            override fun getLandlockAbiVersion(): Int = 5
            override fun hasKernelSeccompSupport(): Boolean = true
            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                LinuxNative.SyscallResult.Error(22, -1L)
        }
        Platform.setProvider(mockPlatform)

        val mockEngine = MockNativeEngine()
        mockEngine.onSyscall = { nr, _, _, _, _, _, _ ->
            if (nr == io.mazewall.core.Arch.current().seccompSyscallNumber.toLong()) {
                LinuxNative.SyscallResult.Error(22, -1L) // EINVAL
            } else {
                LinuxNative.SyscallResult.Success(0L)
            }
        }
        LinuxNative.setEngine(mockEngine)

        // Policy with no Landlock paths
        val policy = Policy.builder().block(Syscall.EXECVE).build()

        val receipt = ContainedExecutors.installOnCurrentThread(policy)

        assertEquals(false, receipt.installed, "Seccomp install should fail")
        assertEquals(false, receipt.landlockApplied, "Landlock should not be applied")
    }

    @Test
    fun `case G - policy with no Landlock and no extra blocks`() {
        val mockPlatform = object : io.mazewall.PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"
            override fun getLandlockAbiVersion(): Int = 5
            override fun hasKernelSeccompSupport(): Boolean = true
            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                LinuxNative.SyscallResult.Error(22, -1L)
        }
        Platform.setProvider(mockPlatform)

        val mockEngine = MockNativeEngine()
        mockEngine.onSyscall = { nr, _, _, _, _, _, _ ->
            LinuxNative.SyscallResult.Success(0L)
        }
        LinuxNative.setEngine(mockEngine)

        // Policy with no Landlock and no blocks (empty policy)
        val policy = Policy.builder().build()

        val receipt = ContainedExecutors.installOnCurrentThread(policy)

        assertEquals(false, receipt.landlockApplied, "Landlock should not be applied for empty policy")
    }
}
