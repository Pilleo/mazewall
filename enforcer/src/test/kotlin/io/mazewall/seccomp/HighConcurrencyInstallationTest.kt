package io.mazewall.seccomp

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.api.MockPlatformProvider
import io.mazewall.enforcer.api.Platform
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.PolicyCompilationCache
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HighConcurrencyInstallationTest {
    @BeforeEach
    fun setup() {
        val mockProcess = object : io.mazewall.enforcer.api.MockNativeProcess() {
            override fun prctl(command: io.mazewall.platform.syscall.PrctlCommand): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                // PR_GET_SECCOMP is option 21
                if (command is io.mazewall.platform.syscall.PrctlCommand.GetSeccomp) {
                    return LinuxNative.SyscallResult.Success(2L)
                }
                return LinuxNative.SyscallResult.Success(0L)
            }
        }
        LinuxNative.setEngine(MockNativeEngine(process = mockProcess))
        Platform.setProvider(MockPlatformProvider())
        io.mazewall.enforcer.api.PolicyCompilationCache
            .clear()
        PureJavaBpfEngine.clearCache()
    }

    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
        Platform.resetToDefault()
        io.mazewall.enforcer.api.PolicyCompilationCache
            .clear()
        PureJavaBpfEngine.clearCache()
    }

    @Test
    fun `high concurrency installation of identical policies`() {
        val executor = Executors.newFixedThreadPool(16)
        val wrapped = ContainedExecutors.wrap(executor, Policy.builder().build())
        val taskCount = 2000
        val completedCount = AtomicInteger(0)

        // Warm up
        repeat(100) { wrapped.execute { completedCount.incrementAndGet() } }

        val startTime = System.nanoTime()
        repeat(taskCount) {
            wrapped.execute {
                completedCount.incrementAndGet()
            }
        }

        wrapped.shutdown()
        assertTrue(wrapped.awaitTermination(30, TimeUnit.SECONDS), "Executor did not terminate in time")
        val duration = System.nanoTime() - startTime

        assertEquals(taskCount + 100, completedCount.get(), "Not all tasks completed")
        val millis = TimeUnit.NANOSECONDS.toMillis(duration)
        assertTrue(millis < 2000, "Performance improvement not observed: $millis ms")
    }

    @Test
    fun `high concurrency installation with exceptions`() {
        val executor = Executors.newFixedThreadPool(16)
        val wrapped = ContainedExecutors.wrap(executor, Policy.builder().build())
        val taskCount = 1000

        repeat(taskCount) {
            wrapped.execute {
                throw RuntimeException("Simulated task failure")
            }
        }

        wrapped.shutdown()
        wrapped.awaitTermination(10, TimeUnit.SECONDS)
    }
}
