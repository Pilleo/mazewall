package io.mazewall.enforcer.internal

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.api.Platform
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
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
        System.setProperty("io.mazewall.fallback", "FAIL")

        val mockPlatform = object : io.mazewall.enforcer.api.PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"

            override fun getLandlockAbiVersion(): Int = 5

            override fun getLandlockCapability(): io.mazewall.enforcer.api.KernelCapability<Int> =
                io.mazewall.enforcer.api.KernelCapability
                .Available(5)

            override fun hasKernelSeccompSupport(): Boolean = true

            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                LinuxNative.SyscallResult.Error(22, -1L) // EINVAL is the expected bogus-mode sanity result
        }
        Platform.setProvider(mockPlatform)

        val mockEngine = MockNativeEngine()
        mockEngine.process.onPrctl = { command ->
            LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) 2L else 0L)
        }
        mockEngine.onSyscall = { nr, _, _, _, _, _, _ ->
            if (nr == io.mazewall.platform.syscall.Arch
                .current()
                .seccompSyscallNumber
                .toLong()
            ) {
                LinuxNative.SyscallResult.Success(0L)
            } else {
                LinuxNative.SyscallResult.Success(0L)
            }
        }
        LinuxNative.setEngine(mockEngine)

        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).block(Syscall.EXECVE).build()
        val delegate = Executors.newSingleThreadExecutor()
        val wrapper = ContainedExecutorWrapper(delegate, policy.definition)

        // First task
        val future1 = wrapper.submit(Runnable {})
        future1.get()

        // Get depth from worker thread (need to capture it inside the task)
        var depthAfterTask1: Int = -1
        val future2 = wrapper.submit(
            Runnable {
            depthAfterTask1 = ContainmentStateRegistry.threadState.filterDepth
        },
        )
        future2.get()

        // Second task to verify depth doesn't increase
        var depthAfterTask2: Int = -1
        val future3 = wrapper.submit(
            Runnable {
            depthAfterTask2 = ContainmentStateRegistry.threadState.filterDepth
        },
        )
        future3.get()

        // Depth should be the same after task 2 as after task 1 (not increased)
        assertEquals(
            depthAfterTask1,
            depthAfterTask2,
            "Filter depth should not increase between successful tasks on the same worker thread. " +
                "After task 1: $depthAfterTask1, after task 2: $depthAfterTask2",
        )
        assertTrue(depthAfterTask1 > 0, "A successful-install fixture must record an applied filter")

        delegate.shutdown()
        delegate.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)
    }

    @Test
    fun `successful install is not rewound`() {
        System.setProperty("io.mazewall.fallback", "FAIL")

        val mockPlatform = object : io.mazewall.enforcer.api.PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"

            override fun getLandlockAbiVersion(): Int = 5

            override fun getLandlockCapability(): io.mazewall.enforcer.api.KernelCapability<Int> =
                io.mazewall.enforcer.api.KernelCapability
                .Available(5)

            override fun hasKernelSeccompSupport(): Boolean = true

            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                LinuxNative.SyscallResult.Error(22, -1L) // EINVAL is the expected bogus-mode sanity result
        }
        Platform.setProvider(mockPlatform)

        val mockEngine = MockNativeEngine()
        mockEngine.process.onPrctl = { command ->
            LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) 2L else 0L)
        }
        mockEngine.onSyscall = { nr, _, _, _, _, _, _ ->
            LinuxNative.SyscallResult.Success(0L)
        }
        LinuxNative.setEngine(mockEngine)

        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).block(Syscall.EXECVE).build()
        val delegate = Executors.newSingleThreadExecutor()
        val wrapper = ContainedExecutorWrapper(delegate, policy.definition)

        // Run a successful task
        val future = wrapper.submit(Runnable {})
        future.get()

        val retainedDepth = delegate.submit(Callable { ContainmentStateRegistry.threadState.filterDepth }).get(5, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(retainedDepth > 0, "Task completion must retain irreversible installation state")

        delegate.shutdown()
        delegate.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)
    }

    @Test
    fun `failed install prevents target entry and publishes no applied filter`() {
        val mockPlatform = object : io.mazewall.enforcer.api.PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"

            override fun getLandlockAbiVersion(): Int = 5

            override fun getLandlockCapability(): io.mazewall.enforcer.api.KernelCapability<Int> =
                io.mazewall.enforcer.api.KernelCapability
                .Available(5)

            override fun hasKernelSeccompSupport(): Boolean = true

            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                LinuxNative.SyscallResult.Error(22, -1L) // EINVAL is the expected bogus-mode sanity result
        }
        Platform.setProvider(mockPlatform)

        val mockEngine = MockNativeEngine()
        // Thread-scoped installation uses prctl; reject before the publication callback.
        mockEngine.process.onPrctl = { command ->
            if (command is PrctlCommand.SetSeccomp) LinuxNative.SyscallResult.Error(22, -1L) else LinuxNative.SyscallResult.Success(0L)
        }
        // Also reject the raw syscall route used by process-wide installation.
        mockEngine.onSyscall = { nr, _, _, _, _, _, _ ->
            if (nr == io.mazewall.platform.syscall.Arch
                .current()
                .seccompSyscallNumber
                .toLong()
            ) {
                LinuxNative.SyscallResult.Error(22, -1)
            } else {
                LinuxNative.SyscallResult.Success(0L)
            }
        }
        LinuxNative.setEngine(mockEngine)

        // Policy WITHOUT Landlock paths so Landlock is not applied
        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).block(Syscall.EXECVE).build()
        val delegate = Executors.newSingleThreadExecutor()
        val wrapper = ContainedExecutorWrapper(delegate, policy.definition)

        val calls = java.util.concurrent.atomic
            .AtomicInteger()
        try {
            val future = wrapper.submit(Callable { calls.incrementAndGet() })
            kotlin.test.assertFailsWith<java.util.concurrent.ExecutionException> { future.get(5, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(0, calls.get())
            val workerState = delegate.submit(Callable { ContainmentStateRegistry.threadState }).get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(0, workerState.filterDepth, "Pre-apply failure must not publish an installed filter")
        } finally {
            delegate.shutdown()
            check(delegate.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
        }
    }
}
