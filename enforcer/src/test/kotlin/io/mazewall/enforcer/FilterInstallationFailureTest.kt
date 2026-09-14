package io.mazewall.enforcer

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.KernelCapability
import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.api.Platform
import io.mazewall.enforcer.api.PlatformProvider
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.internal.ffi.RealPlatformProvider
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
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
        System.clearProperty("io.mazewall.fallback")
        LinuxNative.resetToDefault()
        Platform.resetToDefault()
        ContainmentStateRegistry.threadState = ContainerState()
    }

    @Test
    fun `failed installation records engine progress without inventing policy installation`() {
        val mockPlatform = object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"

            override fun getLandlockAbiVersion(): Int = 5

            override fun getLandlockCapability(): KernelCapability<Int> = KernelCapability.Available(5)

            override fun hasKernelSeccompSupport(): Boolean = true

            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Error(22, -1)
        }
        Platform.setProvider(mockPlatform)

        val mockEngine = MockNativeEngine(
            process = io.mazewall.enforcer.api.MockNativeProcess().apply {
            onPrctl = { command ->
                if (command is io.mazewall.platform.syscall.PrctlCommand.SetSeccomp) {
                    LinuxNative.SyscallResult.Error(22, -1L)
                } else {
                    LinuxNative.SyscallResult.Success(0L)
                }
            }
        },
        )

        // Policy WITHOUT filesystem paths — Landlock will NOT be applied
        val policy = Policy.builder().block(Syscall.EXECVE).build()

        // PureJavaBpfEngine.install calls LinuxNative.raw.syscall(SECCOMP_SET_MODE_FILTER, ...)
        mockEngine.onSyscall = { nr, _, _, _, _, _, _ ->
            if (nr == io.mazewall.platform.syscall.Arch
                .current()
                .seccompSyscallNumber
                .toLong()
            ) {
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

        assertEquals(initialState.filterDepth, ContainmentStateRegistry.threadState.filterDepth)
        assertEquals(initialState.syscallActions, ContainmentStateRegistry.threadState.syscallActions)
        kotlin.test.assertIs<io.mazewall.seccomp.SeccompInstallationState.Failed>(ContainmentStateRegistry.threadState.engineState)
        assertNull(ContainmentStateRegistry.threadState.landlockPolicy)
    }

    @Test
    fun `test state is NOT reverted on failure when Landlock IS applied`() {
        val mockPlatform = object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"

            override fun getLandlockAbiVersion(): Int = 5

            override fun getLandlockCapability(): KernelCapability<Int> = KernelCapability.Available(5)

            override fun hasKernelSeccompSupport(): Boolean = true

            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Error(22, -1)
        }
        Platform.setProvider(mockPlatform)

        val mockEngine = MockNativeEngine()

        // Policy WITH filesystem paths — Landlock WILL be applied (and is irreversible)
        val policy = Policy.builder().allowFsRead("/tmp").build()

        // PureJavaBpfEngine.install calls LinuxNative.raw.syscall(SECCOMP_SET_MODE_FILTER, ...)
        mockEngine.onSyscall = { nr, _, _, _, _, _, _ ->
            if (nr == io.mazewall.platform.syscall.Arch
                .current()
                .seccompSyscallNumber
                .toLong()
            ) {
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

    @Test
    fun `test WARN_AND_BYPASS failure path returns non-installed receipt with landlock in force`() {
        System.setProperty("io.mazewall.fallback", "WARN_AND_BYPASS")
        val mockPlatform = object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"

            override fun getLandlockAbiVersion(): Int = 5

            override fun getLandlockCapability(): KernelCapability<Int> = KernelCapability.Available(5)

            override fun hasKernelSeccompSupport(): Boolean = true

            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Error(22, -1)
        }
        Platform.setProvider(mockPlatform)

        val mockEngine = MockNativeEngine()

        // Policy WITH filesystem paths — Landlock WILL be applied before the seccomp failure
        val policy = Policy.builder().allowFsRead("/tmp").build()

        mockEngine.onSyscall = { nr, _, _, _, _, _, _ ->
            if (nr == io.mazewall.platform.syscall.Arch
                .current()
                .seccompSyscallNumber
                .toLong()
            ) {
                LinuxNative.SyscallResult.Error(22, -1) // EINVAL
            } else {
                LinuxNative.SyscallResult.Success(42L)
            }
        }

        LinuxNative.setEngine(mockEngine)

        val receipt = ContainedExecutors.installOnCurrentThread(policy)

        // VERIFY: no exception is thrown under WARN_AND_BYPASS; receipt reports uncontained,
        // but Landlock remains in force (irreversible).
        assertEquals(false, receipt.installed)
        assertEquals(true, receipt.landlockApplied)
    }
}
