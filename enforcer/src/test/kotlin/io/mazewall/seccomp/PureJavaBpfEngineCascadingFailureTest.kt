package io.mazewall.seccomp

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.Policy
import io.mazewall.compile
import io.mazewall.core.Arch
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import io.mazewall.enforcer.ContainerState
import io.mazewall.enforcer.ThreadStateRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PureJavaBpfEngineCascadingFailureTest {

    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
        PureJavaBpfEngine.clearCache()
        io.mazewall.PolicyCompilationCache.clear()
        ThreadStateRegistry.state = ContainerState()
    }

    @Test
    fun `test cascading failure when prctl is blocked by existing filter`() {
        val mockProcess = object : io.mazewall.MockNativeProcess() {
            context(_: io.mazewall.NativeTransaction)
            override fun prctl(command: io.mazewall.core.PrctlCommand): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                lastPrctlCommand = command
                return if (command is io.mazewall.core.PrctlCommand.GetSeccomp) {
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
        ThreadStateRegistry.state = ContainerState(
            syscallActions = mapOf(Syscall.PRCTL to SeccompAction.ACT_ERRNO)
        )

        // 2. Try to install a new policy that ALLOWS prctl.
        val policy = Policy.builder()
            .allow(Syscall.PRCTL)
            .build()
        val compiled = policy.definition.compile(Arch.current())

        // The installation should succeed because the engine realizes prctl is already blocked
        // by a previous filter and skips the verification call that would otherwise fail.
        PureJavaBpfEngine.install(compiled)
    }
}
