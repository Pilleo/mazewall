package io.mazewall.profiler

import io.mazewall.Policy
import io.mazewall.PolicyDefinition
import io.mazewall.Uncompiled
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.ProcessLauncher
import io.mazewall.core.SocketManager
import io.mazewall.profiler.engine.ProfilerInstallerInterface
import io.mazewall.profiler.engine.TraceEvent
import io.mazewall.profiler.internal.DaemonContext
import io.mazewall.profiler.internal.ProfilerDaemonManager
import io.mazewall.profiler.internal.ProfilerTraceListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.io.InputStream
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProfilerTest {

    class MockProcess(private val pid: Long, private val stdout: String = "") : Process() {
        private var alive = true
        override fun destroy() { alive = false }
        override fun exitValue(): Int = 0
        override fun waitFor(): Int = 0
        override fun getOutputStream(): java.io.OutputStream = java.io.ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(stdout.toByteArray())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun pid(): Long = pid
        override fun isAlive(): Boolean = alive
    }

    class MockProcessLauncher : ProcessLauncher {
        var startProcessCalled = false
        var lastArgs: List<String>? = null
        var mockProcess: Process = MockProcess(8888L, io.mazewall.profiler.engine.DAEMON_READY_SENTINEL + "\n")
        val shutdownHooks = mutableListOf<Thread>()

        override fun startProcess(args: List<String>, redirectErrorStream: Boolean): Process {
            startProcessCalled = true
            lastArgs = args
            return mockProcess
        }

        override fun addShutdownHook(hook: Thread) {
            shutdownHooks.add(hook)
        }

        override fun removeShutdownHook(hook: Thread) {
            shutdownHooks.remove(hook)
        }

        override fun createTempDirectory(prefix: String, vararg attrs: FileAttribute<*>): Path {
            return java.nio.file.Paths.get("/tmp/mock-profiler-dir")
        }

        override fun createTempDirectory(dir: Path, prefix: String, vararg attrs: FileAttribute<*>): Path {
            return java.nio.file.Paths.get("/tmp/fallback-mock-profiler-dir")
        }

        override fun deleteIfExists(path: Path): Boolean = true
        override fun exists(path: Path): Boolean = true
    }

    class MockSocketManager : SocketManager {
        var connectCalled = false
        var closeCalledCount = AtomicInteger(0)

        override fun createUnixServer(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, io.mazewall.core.FdState.Open> = FileDescriptor.unsafe(20)
        override fun accept(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, io.mazewall.core.FdState.Open>): FileDescriptor<FileDescriptorRole.UnixSocket, io.mazewall.core.FdState.Open> = FileDescriptor.unsafe(21)
        override fun connect(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, io.mazewall.core.FdState.Open> {
            connectCalled = true
            return FileDescriptor.unsafe(22)
        }
        override fun close(fd: FileDescriptor<*, io.mazewall.core.FdState.Open>) {
            closeCalledCount.incrementAndGet()
        }
        override fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, io.mazewall.core.FdState.Open>): FileDescriptor<FileDescriptorRole.SeccompNotif, io.mazewall.core.FdState.Open>? = null
        override fun sendDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, io.mazewall.core.FdState.Open>, fdToSend: FileDescriptor<*, io.mazewall.core.FdState.Open>): Boolean = true
    }

    @AfterEach
    fun tearDown() {
        // Reset Profiler providers back to default
        Profiler.daemonManagerProvider = { ProfilerDaemonManager.getInstance() }
        Profiler.installerProvider = io.mazewall.profiler.engine.RealProfilerInstaller
    }

    @Test
    fun `test profile with mocked daemon and installer`() {
        val launcher = MockProcessLauncher()
        val socket = MockSocketManager()
        val mockDaemonManager = ProfilerDaemonManager(io.mazewall.LinuxNative, socket, launcher)

        val mockInstaller = object : ProfilerInstallerInterface {
            var installCalled = false
            override fun installProfilingFilterForThread(
                socketPath: String,
                policy: PolicyDefinition<*>,
                accumulatedLogs: MutableList<TraceEvent>,
                stackTracesMap: MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
                pathCache: MutableMap<String, Long>,
                processWide: Boolean,
                startTraceListener: (
                    Int,
                    MutableList<TraceEvent>,
                    MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
                    MutableMap<String, Long>,
                    CountDownLatch
                ) -> Unit
            ) {
                installCalled = true
                // Simulate successful installation without doing anything native
            }
        }

        Profiler.daemonManagerProvider = { mockDaemonManager }
        Profiler.installerProvider = mockInstaller

        val result = Profiler.profile {
            "success"
        }

        assertEquals("success", result.value)
        assertTrue(mockInstaller.installCalled)
        assertNotNull(result.behavior)
    }

    @Test
    fun `test wrap executor submissions with mocked providers`() {
        val launcher = MockProcessLauncher()
        val socket = MockSocketManager()
        val mockDaemonManager = ProfilerDaemonManager(io.mazewall.LinuxNative, socket, launcher)

        val mockInstaller = object : ProfilerInstallerInterface {
            var installCount = 0
            override fun installProfilingFilterForThread(
                socketPath: String,
                policy: PolicyDefinition<*>,
                accumulatedLogs: MutableList<TraceEvent>,
                stackTracesMap: MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
                pathCache: MutableMap<String, Long>,
                processWide: Boolean,
                startTraceListener: (
                    Int,
                    MutableList<TraceEvent>,
                    MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
                    MutableMap<String, Long>,
                    CountDownLatch
                ) -> Unit
            ) {
                installCount++
            }
        }

        Profiler.daemonManagerProvider = { mockDaemonManager }
        Profiler.installerProvider = mockInstaller

        val delegate = Executors.newSingleThreadExecutor()
        try {
            val wrapper = Profiler.wrap(delegate, Policy.PURE_COMPUTE_UNSAFE)

            val future1 = wrapper.submit(java.util.concurrent.Callable { "task1" })
            assertEquals("task1", future1.get())

            val future2 = wrapper.submit(Runnable { /* noop */ }, "task2")
            assertEquals("task2", future2.get())

            val future3 = wrapper.submit(Runnable { /* noop */ })
            future3.get()

            wrapper.shutdown()
            assertTrue(mockInstaller.installCount > 0)
        } finally {
            delegate.shutdownNow()
        }
    }

    @Test
    fun `test shutdown with listeners`() {
        Profiler.shutdown()
    }
}
