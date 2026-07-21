package io.mazewall.enforcer.internal

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeProcess
import io.mazewall.Platform
import io.mazewall.PlatformProvider
import io.mazewall.RealPlatformProvider
import io.mazewall.PolicyDefinition
import io.mazewall.SeccompMode
import io.mazewall.core.PrctlCommand
import io.mazewall.enforcer.ContainerState
import io.mazewall.enforcer.ThreadStateRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ContainedExecutorWrapperRollbackTest {

    val policyDefinition = PolicyDefinition<io.mazewall.PolicyScope.ProcessWideSafe>()

    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
        Platform.resetToDefault()
        ThreadStateRegistry.state = ContainerState()
    }

    @Test
    fun `test wrapper installation failure rolls back thread local state on any platform`() {
        val delegate = Executors.newSingleThreadExecutor()
        val wrapper = ContainedExecutorWrapper(delegate, policyDefinition)

        // Set up platform provider and MockNativeEngine with toggleable failure
        var seccompShouldFail = false

        val mockPlatform = object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"
            override fun getLandlockAbiVersion(): Int = 5
            override fun hasKernelSeccompSupport(): Boolean = true
            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                LinuxNative.SyscallResult.Error(22, -1) // EINVAL
            override fun getSeccompMode(): SeccompMode =
                if (seccompShouldFail) SeccompMode.Disabled else SeccompMode.Filter
        }
        Platform.setProvider(mockPlatform)

        val mockEngine = MockNativeEngine()
        val mockProcess = mockEngine.process as MockNativeProcess
        mockProcess.onPrctl = { _, command ->
            if (command is PrctlCommand.GetSeccomp) {
                if (seccompShouldFail) {
                    LinuxNative.SyscallResult.Success(0L)
                } else {
                    LinuxNative.SyscallResult.Success(2L)
                }
            } else {
                LinuxNative.SyscallResult.Success(0L)
            }
        }

        mockEngine.onSyscall = { _, nr, _, _, _, _, _, _ ->
            if (nr == io.mazewall.core.Arch.current().seccompSyscallNumber.toLong() && seccompShouldFail) {
                LinuxNative.SyscallResult.Error(22, -1) // EINVAL
            } else {
                LinuxNative.SyscallResult.Success(0L)
            }
        }
        LinuxNative.setEngine(mockEngine)

        // 1. Capture initial thread local state of the single worker thread (installation succeeds)
        var initialThreadState: ContainerState? = null
        wrapper.submit {
            initialThreadState = ThreadStateRegistry.state
        }.get()

        assertNotNull(initialThreadState)

        // Reset the thread local state directly on the delegate worker thread so that the next execution tries to install again
        val resetState = ContainerState()
        delegate.submit {
            ThreadStateRegistry.state = resetState
        }.get()

        // 2. Trigger failure on seccomp installation
        seccompShouldFail = true

        // 3. Submit a task we expect to fail during policy installation
        var taskExecuted = false
        val future = wrapper.submit {
            taskExecuted = true
        }

        val ex = assertThrows(java.util.concurrent.ExecutionException::class.java) {
            future.get()
        }
        assertNotNull(ex.cause)
        assertFalse(taskExecuted)

        // 4. Restore platform and engine
        Platform.resetToDefault()
        LinuxNative.resetToDefault()

        // 5. Submit another task to retrieve the thread's state directly via delegate and assert it matches the reset state
        var finalThreadState: ContainerState? = null
        delegate.submit {
            finalThreadState = ThreadStateRegistry.state
        }.get()

        // Since the previous failed installation rolled back the state, the state on the thread should be equal
        // to resetState (which is the initial state we reset it to before the failed run).
        assertEquals(resetState, finalThreadState)

        delegate.shutdown()
        delegate.awaitTermination(1, TimeUnit.SECONDS)
    }
}
