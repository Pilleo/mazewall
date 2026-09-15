package io.mazewall.seccomp

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.compile
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PureJavaBpfEngineCascadingFailureTest {
    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
        io.mazewall.enforcer.api.PolicyCompilationCache
            .clear()
        ContainmentStateRegistry.threadState = ContainerState()
    }

    @Test
    fun `test cascading failure when prctl is blocked by existing filter`() {
        val mockProcess = object : io.mazewall.enforcer.api.MockNativeProcess() {
            override fun prctl(command: io.mazewall.platform.syscall.PrctlCommand): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                lastPrctlCommand = command
                return if (command is io.mazewall.platform.syscall.PrctlCommand.GetSeccomp) {
                    LinuxNative.SyscallResult.Error(1, -1L) // EPERM
                } else {
                    LinuxNative.SyscallResult.Success(0L)
                }
            }
        }
        val mockEngine = MockNativeEngine(process = mockProcess)
        LinuxNative.setEngine(mockEngine)

        // 1. Simulate an existing state where prctl is blocked.
        // This simulates a previous filter that was already installed in the kernel.
        ContainmentStateRegistry.threadState = ContainerState(
            syscallActions = mapOf(Syscall.PRCTL to SeccompAction.ACT_ERRNO()),
        )

        // 2. Try to install a new policy that ALLOWS prctl.
        val policy = Policy
            .builder()
            .allow(Syscall.PRCTL)
            .build()
        val compiled = policy.definition.compile(Arch.current())

        // The installation should succeed because the engine realizes prctl is already blocked
        // by a previous filter and skips the verification call that would otherwise fail.
        PureJavaBpfEngine.install(compiled)
    }
}
