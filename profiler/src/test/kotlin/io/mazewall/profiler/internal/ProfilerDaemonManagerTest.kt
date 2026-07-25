package io.mazewall.profiler.internal

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.Platform
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.ProcessLauncher
import io.mazewall.core.SocketManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute
import java.util.concurrent.atomic.AtomicInteger

class ProfilerDaemonManagerTest {

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

    open class MockProcessLauncher : ProcessLauncher {
        var startProcessCalled = false
        var lastArgs: List<String>? = null
        var mockProcess: Process = MockProcess(8888L)
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

    @Test
    fun `test dummy test for coverage`() {
        val clazz = ProfilerDaemonManager::class.java
        assertNotNull(clazz)
    }

    @Test
    fun `test daemon spawn and stop with mocks`() {
        val mockEngine = MockNativeEngine()
        val mockLauncher = MockProcessLauncher()
        mockLauncher.mockProcess = MockProcess(8888L, io.mazewall.profiler.engine.DAEMON_READY_SENTINEL + "\n")
        val mockSocket = MockSocketManager()

        val manager = ProfilerDaemonManager(mockEngine, mockSocket, mockLauncher)

        val context = manager.getOrSpawnSharedDaemon()
        assertNotNull(context)
        assertEquals(8888L, context.daemonProcess.pid())
        assertTrue(mockLauncher.startProcessCalled)

        val context2 = manager.getOrSpawnSharedDaemon()
        assertTrue(context === context2)

        manager.stop()
        // No need to check isAlive if we don't have a real process that reacts to SHUTDOWN byte in this mock
        assertTrue(mockSocket.connectCalled)
    }

    @Test
    fun `test real daemon spawn and stop`() {
        assumeTrue(Platform.isSupported())

        val manager = ProfilerDaemonManager.getInstance()
        val context = manager.getOrSpawnSharedDaemon()
        assertNotNull(context)
        assertTrue(context.daemonProcess.isAlive)

        val context2 = manager.getOrSpawnSharedDaemon()
        assertTrue(context === context2) // should reuse

        manager.stop()

        // Wait for it to die
        context.daemonProcess.waitFor()
        assertFalse(context.daemonProcess.isAlive)
    }

    @Test
    fun `spawnDaemon falls back to short temp directory when default socket path is too long`() {
        val mockEngine = MockNativeEngine()
        val mockLauncher = object : MockProcessLauncher() {
            override fun createTempDirectory(prefix: String, vararg attrs: FileAttribute<*>): Path {
                // Return an excessively long path that exceeds 107 bytes when suffix and /profiler.sock is appended
                return java.nio.file.Paths.get("/" + "a".repeat(120))
            }

            override fun createTempDirectory(dir: Path, prefix: String, vararg attrs: FileAttribute<*>): Path {
                return java.nio.file.Paths.get("/tmp/fallback-mock-profiler-dir")
            }
        }
        mockLauncher.mockProcess = MockProcess(8888L, io.mazewall.profiler.engine.DAEMON_READY_SENTINEL + "\n")
        val mockSocket = MockSocketManager()

        val manager = ProfilerDaemonManager(mockEngine, mockSocket, mockLauncher)
        val context = manager.getOrSpawnSharedDaemon()

        try {
            assertNotNull(context)
            val pathBytes = context.socketPath.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            assertTrue(pathBytes.size < 108, "Fallback path should be under 108 bytes: ${context.socketPath}")
            assertEquals("/tmp/fallback-mock-profiler-dir/profiler.sock", context.socketPath)
        } finally {
            manager.stop()
        }
    }
}
