package io.mazewall.enforcer

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeProcess
import io.mazewall.MockPlatformProvider
import io.mazewall.Platform
import io.mazewall.Policy
import io.mazewall.PolicyCompilationCache
import io.mazewall.core.PrctlCommand
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.seccomp.PureJavaBpfEngine
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertNull

class InstallationReceiptSupervisorSessionTest {
    @BeforeEach
    fun setUp() {
        ContainmentStateRegistry.processState = ContainerState()
        ContainmentStateRegistry.threadState = ContainerState()
        Platform.setProvider(MockPlatformProvider())
        PureJavaBpfEngine.clearCache()
        PolicyCompilationCache.clear()

        val process = MockNativeProcess().apply {
            onPrctl = { command ->
                if (command is PrctlCommand.GetSeccomp) {
                    LinuxNative.SyscallResult.Success(2L)
                } else {
                    LinuxNative.SyscallResult.Success(0L)
                }
            }
        }
        LinuxNative.setEngine(MockNativeEngine(process = process).apply {
            onSyscall = { _, _, _, _, _, _, _ -> LinuxNative.SyscallResult.Success(0L) }
        })
    }

    @AfterEach
    fun tearDown() {
        ContainmentStateRegistry.processState = ContainerState()
        ContainmentStateRegistry.threadState = ContainerState()
        Platform.resetToDefault()
        LinuxNative.resetToDefault()
        PureJavaBpfEngine.clearCache()
        PolicyCompilationCache.clear()
    }

    @Test
    fun `unsupervised filter installation does not report a supervisor session`() {
        val receipt = ContainedExecutors.installOnCurrentThread(Policy.NO_NETWORK.definition)

        assertNull(receipt.supervisorSession)
    }
}
