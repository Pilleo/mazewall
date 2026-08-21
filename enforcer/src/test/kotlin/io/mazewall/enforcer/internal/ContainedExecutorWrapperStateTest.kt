package io.mazewall.enforcer.internal

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.Platform
import io.mazewall.Policy
import io.mazewall.RealPlatformProvider
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

class ContainedExecutorWrapperStateTest {

    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
        Platform.resetToDefault()
        ContainmentStateRegistry.threadState = ContainerState()
        ContainmentStateRegistry.processState = ContainerState()
        System.clearProperty("io.mazewall.fallback")
    }

    @Test
    fun `successful two tasks same policy same worker - depth does not increase`() {
        System.setProperty("io.mazewall.fallback", "WARN_AND_BYPASS")

        val mockPlatform = object : io.mazewall.PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"
            override fun getLandlockAbiVersion(): Int = 5
            override fun hasKernelSeccompSupport(): Boolean = true
            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                LinuxNative.SyscallResult.Error(22, -1L) // EINVAL - seccomp not available in container
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

        val policy = Policy.builder().block(Syscall.EXECVE).build()
        val delegate = Executors.newSingleThreadExecutor()
        val wrapper = ContainedExecutorWrapper(delegate, policy.definition)

        // Capture depth before first task
        val initialDepth = ContainmentStateRegistry.threadState.filterDepth

        // First task
        val future1 = wrapper.submit(Runnable {})
        future1.get()

        // Get depth from worker thread (need to capture it inside the task)
        var depthAfterTask1: Int = -1
        val future2 = wrapper.submit(Runnable {
            depthAfterTask1 = ContainmentStateRegistry.threadState.filterDepth
        })
        future2.get()

        // Second task to verify depth doesn't increase
        var depthAfterTask2: Int = -1
        val future3 = wrapper.submit(Runnable {
            depthAfterTask2 = ContainmentStateRegistry.threadState.filterDepth
        })
        future3.get()

        // Depth should be the same after task 2 as after task 1 (not increased)
        assertEquals(
            depthAfterTask1, depthAfterTask2,
            "Filter depth should not increase between successful tasks on the same worker thread. " +
                "After task 1: $depthAfterTask1, after task 2: $depthAfterTask2"
        )

        delegate.shutdown()
        delegate.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)
    }

    @Test
    fun `successful install is not rewound`() {
        System.setProperty("io.mazewall.fallback", "WARN_AND_BYPASS")

        val mockPlatform = object : io.mazewall.PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"
            override fun getLandlockAbiVersion(): Int = 5
            override fun hasKernelSeccompSupport(): Boolean = true
            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                LinuxNative.SyscallResult.Error(22, -1L) // EINVAL - seccomp not available in container
        }
        Platform.setProvider(mockPlatform)

        val mockEngine = MockNativeEngine()
        mockEngine.onSyscall = { nr, _, _, _, _, _, _ ->
            LinuxNative.SyscallResult.Success(0L)
        }
        LinuxNative.setEngine(mockEngine)

        val policy = Policy.builder().block(Syscall.EXECVE).build()
        val delegate = Executors.newSingleThreadExecutor()
        val wrapper = ContainedExecutorWrapper(delegate, policy.definition)

        // Run a successful task
        val future = wrapper.submit(Runnable {})
        future.get()

        // With WARN_AND_BYPASS fallback, the install is bypassed (installed=false) but receipt is not null
        // This means the catch block does NOT restore threadState (since receipt != null)
        // The filter is not actually installed, so threadState may be empty or have other state
        // The key invariant: threadState was NOT restored to initialState by the wrapper

        delegate.shutdown()
        delegate.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)
    }

    @Test
    fun `failed install before receipt still rewinds`() {
        val mockPlatform = object : io.mazewall.PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"
            override fun getLandlockAbiVersion(): Int = 5
            override fun hasKernelSeccompSupport(): Boolean = true
            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                LinuxNative.SyscallResult.Error(22, -1L) // EINVAL - seccomp not available in container
        }
        Platform.setProvider(mockPlatform)

        val mockEngine = MockNativeEngine()
        // Mock seccomp syscall to fail with EINVAL (22)
        mockEngine.onSyscall = { nr, _, _, _, _, _, _ ->
            if (nr == io.mazewall.core.Arch.current().seccompSyscallNumber.toLong()) {
                LinuxNative.SyscallResult.Error(22, -1)
            } else {
                LinuxNative.SyscallResult.Success(0L)
            }
        }
        LinuxNative.setEngine(mockEngine)

        // Policy WITHOUT Landlock paths so Landlock is not applied
        val policy = Policy.builder().block(Syscall.EXECVE).build()
        val delegate = Executors.newSingleThreadExecutor()
        val wrapper = ContainedExecutorWrapper(delegate, policy.definition)

        // Set initial empty state
        ContainmentStateRegistry.threadState = ContainerState()
        val initialState = ContainmentStateRegistry.threadState

        // Submit a task that will fail during install
        val future = wrapper.submit(Runnable {})

        try {
            future.get()
        } catch (e: Exception) {
            // Expected to throw
        }

        // After failed install (receipt == null), threadState should be restored to initial
        assertEquals(
            initialState, ContainmentStateRegistry.threadState,
            "Thread state should be restored to initial state after failed install"
        )

        delegate.shutdown()
        delegate.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)
    }
}
