package io.mazewall.seccomp

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeProcess
import io.mazewall.MockPlatformProvider
import io.mazewall.Platform
import io.mazewall.Policy
import io.mazewall.compile
import io.mazewall.core.Arch
import io.mazewall.core.PrctlCommand
import io.mazewall.enforcer.ContainerState
import io.mazewall.enforcer.ProcessStateRegistry
import io.mazewall.enforcer.ThreadStateRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import kotlin.test.assertEquals

class PureJavaBpfEngineThreadStateSynchronizationTest {

    @BeforeEach
    fun setUp() {
        ProcessStateRegistry.state = ContainerState()
        ThreadStateRegistry.state = ContainerState()
        Platform.setProvider(MockPlatformProvider())
        LinuxNative.resetToDefault()
        PureJavaBpfEngine.clearCache()
        io.mazewall.PolicyCompilationCache.clear()
    }

    @AfterEach
    fun tearDown() {
        ProcessStateRegistry.state = ContainerState()
        ThreadStateRegistry.state = ContainerState()
        Platform.resetToDefault()
        LinuxNative.resetToDefault()
        PureJavaBpfEngine.clearCache()
        io.mazewall.PolicyCompilationCache.clear()
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
            val siblingState = executor.submit<EngineState> {
                PureJavaBpfEngine.state
            }.get()
            assertEquals(EngineState.LoadedImpl, siblingState, "Sibling thread must see the process-wide seccomp state as Loaded")
        } finally {
            executor.shutdown()
        }
    }
}
