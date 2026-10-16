package io.mazewall.enforcer

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.api.MockNativeProcess
import io.mazewall.enforcer.api.MockPlatformProvider
import io.mazewall.enforcer.api.Platform
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.PolicyCompilationCache
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.PrctlCommand
import io.mazewall.seccomp.PureJavaBpfEngine
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class InstallationReceiptSupervisorSessionTest {
    @BeforeEach
    fun setUp() {
        ContainmentStateRegistry.processState = ContainerState()
        ContainmentStateRegistry.threadState = ContainerState()
        Platform.setProvider(MockPlatformProvider())
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
        LinuxNative.setEngine(
            MockNativeEngine(process = process).apply {
            onSyscall = { _, _, _, _, _, _, _ -> LinuxNative.SyscallResult.Success(0L) }
        },
        )
    }

    @AfterEach
    fun tearDown() {
        ContainmentStateRegistry.processState = ContainerState()
        ContainmentStateRegistry.threadState = ContainerState()
        Platform.resetToDefault()
        LinuxNative.resetToDefault()
        PolicyCompilationCache.clear()
    }

    @Test
    fun `unsupervised filter installation does not report a supervisor session`() {
        val receipt = ContainedExecutors.installOnCurrentThread(Policy.NO_NETWORK.definition)

        receipt.use { assertTrue(it.installed) }
    }
}
