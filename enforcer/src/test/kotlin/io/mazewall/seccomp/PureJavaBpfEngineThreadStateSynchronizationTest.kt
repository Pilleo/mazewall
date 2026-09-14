package io.mazewall.seccomp

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.api.MockNativeProcess
import io.mazewall.enforcer.api.MockPlatformProvider
import io.mazewall.enforcer.api.Platform
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
import io.mazewall.platform.syscall.PrctlCommand
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import kotlin.test.assertEquals

class PureJavaBpfEngineThreadStateSynchronizationTest {
    @BeforeEach
    fun setUp() {
        ContainmentStateRegistry.processState = ContainerState()
        ContainmentStateRegistry.threadState = ContainerState()
        Platform.setProvider(MockPlatformProvider())
        LinuxNative.resetToDefault()
        PureJavaBpfEngine.clearCache()
        io.mazewall.enforcer.api.PolicyCompilationCache
            .clear()
    }

    @AfterEach
    fun tearDown() {
        ContainmentStateRegistry.processState = ContainerState()
        ContainmentStateRegistry.threadState = ContainerState()
        Platform.resetToDefault()
        LinuxNative.resetToDefault()
        PureJavaBpfEngine.clearCache()
        io.mazewall.enforcer.api.PolicyCompilationCache
            .clear()
    }

    @Test
    fun `test installOnProcess synchronizes engineState to sibling threads`() {
        // Setup mock engine
        val mockProcess = MockNativeProcess()
        mockProcess.onPrctl = { command ->
            if (command is PrctlCommand.GetSeccomp) {
                LinuxNative.SyscallResult.Success(2L)
            } else {
                LinuxNative.SyscallResult.Success(0L)
            }
        }
        val mockEngine = MockNativeEngine(process = mockProcess)
        mockEngine.onSyscall = { _, _, _, _, _, _, _ ->
            LinuxNative.SyscallResult.Success(0L)
        }
        LinuxNative.setEngine(mockEngine)

        val policy = Policy.PURE_COMPUTE_UNSAFE.definition.compile(Arch.current())

        // Ensure initially uninitialized/unprivileged
        assertEquals(EngineState.UnprivilegedImpl, PureJavaBpfEngine.state)

        // Run installOnProcess on main thread
        PureJavaBpfEngine.installOnProcess(policy)

        // Main thread should be LoadedImpl (Verified)
        assertEquals(EngineState.LoadedImpl, PureJavaBpfEngine.state)

        // Sibling thread should also see LoadedImpl (Verified)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val siblingState = executor
                .submit<EngineState> {
                PureJavaBpfEngine.state
            }.get()
            assertEquals(EngineState.LoadedImpl, siblingState, "Sibling thread must see the process-wide seccomp state as Loaded")
        } finally {
            executor.shutdown()
        }
    }
}
