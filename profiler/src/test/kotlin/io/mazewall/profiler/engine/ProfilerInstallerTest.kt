package io.mazewall.profiler.engine

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.Policy
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertTrue

class ProfilerInstallerTest {
    private val native = MockNativeEngine().apply {
        syscallResult = LinuxNative.SyscallResult.Success(100L)
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
                    native = native,
                    userNotifSupported = true,
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
        thread.join()

        val ex = errorRef.get()
        assertTrue(ex is IllegalStateException)
        assertTrue(ex.message?.contains("Simulated connection retry failure") == true)
    }

    @Test
    fun `test main thread waits for coordinator thread to finish`() {
        val accumulatedLogs = CopyOnWriteArrayList<TraceEvent>()
        val pathCache = ConcurrentHashMap<String, Long>()
        val errorRef = AtomicReference<Throwable?>(null)
        val startTime = System.currentTimeMillis()

        // Run on a dedicated thread to avoid contaminating the main JUnit thread
        val thread = Thread {
            try {
                val currentThread = Thread.currentThread()
                ProfilerInstaller.installProfilingFilterForThread(
                    native = native,
                    userNotifSupported = true,
                    socketPath = "/tmp/nonexistent-path.sock",
                    policy = Policy.PURE_COMPUTE_UNSAFE.definition,
                    accumulatedLogs = accumulatedLogs,
                    stackTracesMap = null,
                    pathCache = pathCache,
                    connectWithRetry = { _ ->
                        Thread.sleep(500)
                        throw IllegalStateException("Delayed error")
                    },
                    startTraceListener = { _, _, _, _, latch -> latch.countDown() },
                )
            } catch (t: Throwable) {
                errorRef.set(t)
            }
        }
        thread.start()
        thread.join()

        val duration = System.currentTimeMillis() - startTime
        val ex = errorRef.get()

        // This assertion will fail if the race condition is present (duration will be < 500ms)
        assertTrue(duration >= 500, "Main thread should have waited at least 500ms, but took $duration ms")
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
                    native = native,
                    userNotifSupported = true,
                    socketPath = "/tmp/nonexistent-path.sock",
                    policy = Policy.PURE_COMPUTE_UNSAFE.definition,
                    accumulatedLogs = accumulatedLogs,
                    stackTracesMap = null,
                    pathCache = pathCache,
                    connectWithRetry = { _ ->
                        // Return a dummy FD that will fail sendDescriptor
                        999
                    },
                    startTraceListener = { _, _, _, _, latch -> latch.countDown() },
                )
            } catch (t: Throwable) {
                errorRef.set(t)
            }
        }
        thread.start()
        thread.join()

        val ex = errorRef.get()
        assertTrue(ex is IllegalStateException)
        assertTrue(ex.message?.contains("Failed to send seccomp listener FD to daemon") == true)
    }
}
