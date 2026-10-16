package io.mazewall.profiler.internal.engine
import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.api.Policy
import io.mazewall.platform.lifecycle.TestFileDescriptors
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import io.mazewall.testing.awaitCondition
import io.mazewall.testing.awaitOrFail
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertTrue

class ProfilerInstallerTest {
    private val native = MockNativeEngine().apply {
        syscallResult = LinuxNative.SyscallResult.Success(100L)
    }

    @Test
    fun `installer accepts only an explicitly captured native context`() {
        assertTrue(
            ProfilerInstaller::class.java.declaredMethods
                .filter { it.name == "installProfilingFilterForThread" }
                .all { it.parameterTypes.firstOrNull() == ProfilerInstallationContext::class.java },
            "ProfilerInstaller must not resolve a mutable LinuxNative singleton during installation",
        )
    }

    @Test
    fun `test coordinator thread handles connection retry failure`() {
        val accumulatedLogs = CopyOnWriteArrayList<TraceEvent>()
        val pathCache = ConcurrentHashMap<String, Long>()
        val errorRef = AtomicReference<Throwable?>(null)

        // Run on a dedicated thread to avoid contaminating the main JUnit thread
        val thread = Thread {
            try {
                val currentThread = Thread.currentThread()
                ProfilerInstaller.installProfilingFilterForThread(
                    context = ProfilerInstallationContext(native, userNotifSupported = true),
                    socketPath = "/tmp/nonexistent-path.sock",
                    policy = Policy.PURE_COMPUTE_UNSAFE.definition,
                    accumulatedLogs = accumulatedLogs,
                    stackTracesMap = null,
                    pathCache = pathCache,
                    connectWithRetry = { _ ->
                        throw IllegalStateException("Simulated connection retry failure")
                    },
                    startTraceListener = { _, _, _, _, latch -> latch.countDown() },
                )
            } catch (t: Throwable) {
                errorRef.set(t)
            }
        }
        thread.start()
        awaitCondition(description = "coordinator connection failure reached installer") { !thread.isAlive }

        val ex = errorRef.get()
        assertTrue(ex is IllegalStateException)
        assertTrue(ex.message?.contains("Simulated connection retry failure") == true)
    }

    @Test
    fun `test main thread waits for coordinator thread to finish`() {
        val accumulatedLogs = CopyOnWriteArrayList<TraceEvent>()
        val pathCache = ConcurrentHashMap<String, Long>()
        val errorRef = AtomicReference<Throwable?>(null)
        val coordinatorEntered = CountDownLatch(1)
        val releaseCoordinator = CountDownLatch(1)

        // Run on a dedicated thread to avoid contaminating the main JUnit thread
        val thread = Thread {
            try {
                val currentThread = Thread.currentThread()
                ProfilerInstaller.installProfilingFilterForThread(
                    context = ProfilerInstallationContext(native, userNotifSupported = true),
                    socketPath = "/tmp/nonexistent-path.sock",
                    policy = Policy.PURE_COMPUTE_UNSAFE.definition,
                    accumulatedLogs = accumulatedLogs,
                    stackTracesMap = null,
                    pathCache = pathCache,
                    connectWithRetry = { _ ->
                        coordinatorEntered.countDown()
                        releaseCoordinator.awaitOrFail("test releases coordinator connection failure")
                        throw IllegalStateException("Delayed error")
                    },
                    startTraceListener = { _, _, _, _, latch -> latch.countDown() },
                )
            } catch (t: Throwable) {
                errorRef.set(t)
            }
        }
        thread.start()
        try {
            coordinatorEntered.awaitOrFail("coordinator entered connection attempt")
            awaitCondition(description = "installer waits for coordinator completion") {
                thread.state == Thread.State.TIMED_WAITING
            }
            assertTrue(errorRef.get() == null, "installer cannot complete while the coordinator is blocked")
        } finally {
            releaseCoordinator.countDown()
            awaitCondition(description = "installer returns coordinator failure") { !thread.isAlive }
        }
        val ex = errorRef.get()

        assertTrue(ex is IllegalStateException)
        assertTrue(ex.message?.contains("Delayed error") == true)
    }

    @Test
    fun `test coordinator thread handles send descriptor failure`() {
        native.networking.sendmsgResult = LinuxNative.SyscallResult.Error(9, -1) // EBADF

        val accumulatedLogs = CopyOnWriteArrayList<TraceEvent>()
        val pathCache = ConcurrentHashMap<String, Long>()
        val errorRef = AtomicReference<Throwable?>(null)

        // Run on a dedicated thread to avoid contaminating the main JUnit thread
        val thread = Thread {
            try {
                val currentThread = Thread.currentThread()
                ProfilerInstaller.installProfilingFilterForThread(
                    context = ProfilerInstallationContext(native, userNotifSupported = true),
                    socketPath = "/tmp/nonexistent-path.sock",
                    policy = Policy.PURE_COMPUTE_UNSAFE.definition,
                    accumulatedLogs = accumulatedLogs,
                    stackTracesMap = null,
                    pathCache = pathCache,
                    connectWithRetry = { _ ->
                        // Return a dummy FD that will fail sendDescriptor
                        TestFileDescriptors
                            .replace<io.mazewall.platform.fd.FileDescriptorRole.UnixSocket>(999)
                    },
                    startTraceListener = { _, _, _, _, latch -> latch.countDown() },
                )
            } catch (t: Throwable) {
                errorRef.set(t)
            }
        }
        thread.start()
        awaitCondition(description = "descriptor send failure reached installer") { !thread.isAlive }

        val ex = errorRef.get()
        assertTrue(ex is IllegalStateException)
        assertTrue(ex.message?.contains("Failed to send seccomp listener FD to daemon") == true)
    }
}
