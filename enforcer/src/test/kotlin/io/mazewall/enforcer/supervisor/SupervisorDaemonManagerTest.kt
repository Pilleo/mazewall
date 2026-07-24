package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.ProcessLauncher
import io.mazewall.core.SocketManager
import io.mazewall.core.Tid
import io.mazewall.ffi.NativeConstants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import io.mazewall.ffi.memory.readByte
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SupervisorDaemonManagerTest {

    class MockProcess(private val pid: Long, private val stdout: String = "") : Process() {
        override fun destroy() {}
        override fun exitValue(): Int = 0
        override fun waitFor(): Int = 0
        override fun getOutputStream(): java.io.OutputStream = java.io.ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(stdout.toByteArray())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun pid(): Long = pid
        override fun isAlive(): Boolean = true
    }

    class MockProcessLauncher : ProcessLauncher {
        var startProcessCalled = false
        var lastArgs: List<String>? = null
        var mockProcess: Process = MockProcess(9999L)
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
            return java.nio.file.Paths.get("/tmp/mock-dir")
        }

        override fun deleteIfExists(path: Path): Boolean = true
        override fun exists(path: Path): Boolean = true
    }

    class MockSocketManager : SocketManager {
        var connectCalled = false
        var lastConnectPath: String? = null
        var closeCalledCount = AtomicInteger(0)

        override fun createUnixServer(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, io.mazewall.core.FdState.Open> = FileDescriptor.unsafe(10)
        override fun accept(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, io.mazewall.core.FdState.Open>): FileDescriptor<FileDescriptorRole.UnixSocket, io.mazewall.core.FdState.Open> = FileDescriptor.unsafe(11)
        override fun connect(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, io.mazewall.core.FdState.Open> {
            connectCalled = true
            lastConnectPath = socketPath
            return FileDescriptor.unsafe(12)
        }
        override fun close(fd: FileDescriptor<*, io.mazewall.core.FdState.Open>) {
            closeCalledCount.incrementAndGet()
        }
        override fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, io.mazewall.core.FdState.Open>): FileDescriptor<FileDescriptorRole.SeccompNotif, io.mazewall.core.FdState.Open>? = null
        override fun sendDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, io.mazewall.core.FdState.Open>, fdToSend: FileDescriptor<*, io.mazewall.core.FdState.Open>): Boolean = true
    }

    @Test
    fun `getOrSpawnSharedDaemon spawns daemon and sets ptracer`() {
        val mockEngine = MockNativeEngine()
        val mockLauncher = MockProcessLauncher()
        mockLauncher.mockProcess = MockProcess(9999L, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")
        val mockSocket = MockSocketManager()

        val manager = SupervisorDaemonManager(mockEngine, mockSocket, mockLauncher)

        var prctlCalled = false
        mockEngine.process.onPrctl = { command ->
            if (command is io.mazewall.core.PrctlCommand.SetPtracer && command.tracerPid == 9999L) {
                prctlCalled = true
            }
            LinuxNative.SyscallResult.Success(0L)
        }

        val context = manager.getOrSpawnSharedDaemon()

        assertNotNull(context)
        assertEquals(9999L, context.daemonProcess.pid())
        assertTrue(mockLauncher.startProcessCalled)
        assertTrue(prctlCalled, "prctl(PR_SET_PTRACER) should be called with daemon PID")
    }

    @Test
    fun `stop cleans up daemon and triggers shutdown`() {
        val mockEngine = MockNativeEngine()
        val mockLauncher = MockProcessLauncher()
        mockLauncher.mockProcess = MockProcess(9999L, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")
        val mockSocket = MockSocketManager()

        val manager = SupervisorDaemonManager(mockEngine, mockSocket, mockLauncher)
        manager.getOrSpawnSharedDaemon()

        var writeCalledWithShutdown = false
        mockEngine.memory.onWrite = { _, buf, count ->
            if (count == 1L) {
                val byte = buf.readByte(0L)
                if (byte == 0x53.toByte()) { // 'S'
                    writeCalledWithShutdown = true
                }
            }
            LinuxNative.SyscallResult.Success(count)
        }

        manager.stop()

        assertTrue(mockSocket.connectCalled)
        assertTrue(writeCalledWithShutdown, "Should write shutdown command to daemon socket")
        assertEquals(1, mockSocket.closeCalledCount.get())
    }
}
