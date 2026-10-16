package io.mazewall.seccomp

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.PrctlCommand
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CapturedSeccompBackendTest {
    enum class SwitchAt { PRIVILEGE_SETUP, PUBLICATION, COMPATIBILITY_FALLBACK }

    @AfterEach
    fun restoreRuntime() {
        LinuxNative.resetToDefault()
        Platform.resetToDefault()
        ContainmentStateRegistry.threadState = ContainerState()
        ContainmentStateRegistry.processState = ContainerState()
    }

    @ParameterizedTest
    @EnumSource(SwitchAt::class)
    fun `one selected backend owns native setup installation and verification`(switchAt: SwitchAt) {
        val foreignCalls = AtomicInteger()
        val foreign = MockNativeEngine().apply {
            process.onPrctl = {
                foreignCalls.incrementAndGet()
                LinuxNative.SyscallResult.Error(1, -1L)
            }
            onSyscall = { _, _, _, _, _, _, _ ->
                foreignCalls.incrementAndGet()
                LinuxNative.SyscallResult.Error(1, -1L)
            }
        }
        val installs = AtomicInteger()
        val verifies = AtomicInteger()
        val legacyInstalls = AtomicInteger()
        val selected = MockNativeEngine().apply {
            process.onPrctl = { command ->
                if (command is PrctlCommand.SetNoNewPrivs && switchAt == SwitchAt.PRIVILEGE_SETUP) LinuxNative.setEngine(foreign)
                if (command is PrctlCommand.GetSeccomp) verifies.incrementAndGet()
                if (command is PrctlCommand.SetSeccomp) legacyInstalls.incrementAndGet()
                LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) 2L else 0L)
            }
            onSyscall = { nr, _, _, _, _, _, _ ->
                if (nr == Arch.current().seccompSyscallNumber.toLong()) {
                    installs.incrementAndGet()
                    if (switchAt == SwitchAt.COMPATIBILITY_FALLBACK) {
                        LinuxNative.setEngine(foreign)
                        LinuxNative.SyscallResult.Error(38, -1L)
                    } else {
                        LinuxNative.SyscallResult.Success(0L)
                    }
                } else {
                    LinuxNative.SyscallResult.Success(0L)
                }
            }
        }
        LinuxNative.setEngine(selected)
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build()
        val sandbox = PolicyCompilationCache.getOrCompile(policy.definition, Arch.current())

        if (switchAt == SwitchAt.PUBLICATION) {
            PureJavaBpfEngine.installWithPublication(sandbox, false) { LinuxNative.setEngine(foreign) }
        } else {
            PureJavaBpfEngine.install(sandbox)
        }

        assertEquals(0, foreignCalls.get(), "Global replacement cannot redirect an in-flight native installation")
        assertEquals(1, installs.get())
        assertEquals(1, verifies.get())
        assertEquals(if (switchAt == SwitchAt.COMPATIBILITY_FALLBACK) 1 else 0, legacyInstalls.get())
        assertTrue(ContainmentStateRegistry.threadState.engineState is SeccompInstallationState.Verified)
    }
}
