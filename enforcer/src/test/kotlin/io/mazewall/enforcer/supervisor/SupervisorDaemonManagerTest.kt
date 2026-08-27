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
import io.mazewall.MockProcess
import io.mazewall.MockProcessLauncher
import io.mazewall.MockSocketManager
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.seccomp.SeccompInstallationState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import io.mazewall.ffi.memory.readByte
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SupervisorDaemonManagerTest {

    @AfterEach
    fun resetContainmentState() {
        ContainmentStateRegistry.processState = ContainerState()
        ContainmentStateRegistry.threadState = ContainerState()
    }


    @Test
    fun `spawn is allowed when only an outer OCI seccomp profile is present`() {
        val mockEngine = MockNativeEngine()
        val mockLauncher = MockProcessLauncher()
        mockLauncher.mockProcess = MockProcess(9999L, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")
        val manager = SupervisorDaemonManager(mockEngine, MockSocketManager(), mockLauncher)

        mockEngine.process.onPrctl = {
            LinuxNative.SyscallResult.Success(2L)
        }

        val context = manager.getOrSpawnSharedDaemon()
        assertTrue(mockLauncher.startProcessCalled)
        assertEquals(9999L, context.daemonProcess.pid())
    }

    @Test
    fun `spawn is refused after this JVM applied a mazewall filter`() {
        ContainmentStateRegistry.processState =
            ContainerState(filterDepth = 1, engineState = SeccompInstallationState.Verified)
        val mockEngine = MockNativeEngine()
        val manager = SupervisorDaemonManager(mockEngine, MockSocketManager(), MockProcessLauncher())

        val ex =
            assertThrows<IllegalStateException> {
                manager.getOrSpawnSharedDaemon()
            }
        assertTrue(ex.message!!.contains("mazewall seccomp"))
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

    @Test
    fun `detects unexpected daemon exit and invokes handler`() {
        val mockEngine = MockNativeEngine()
        val mockLauncher = MockProcessLauncher()
        val mockProcess = MockProcess(9999L, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n", exitVal = 42, alive = false)
        mockLauncher.mockProcess = mockProcess
        val mockSocket = MockSocketManager()

        val manager = SupervisorDaemonManager(mockEngine, mockSocket, mockLauncher)

        val exitLatch = CountDownLatch(1)
        val exitCodeReceived = java.util.concurrent.atomic.AtomicInteger(-1)
        manager.onUnexpectedExit = { exitCode ->
            exitCodeReceived.set(exitCode)
            exitLatch.countDown()
        }

        val context = manager.getOrSpawnSharedDaemon()
        assertNotNull(context)

        val completed = exitLatch.await(5, TimeUnit.SECONDS)
        assertTrue(completed, "Expected unexpected exit handler to be invoked")
        assertEquals(42, exitCodeReceived.get())
    }

    @Test
    fun `spawnDaemon falls back to short temp directory when default socket path is too long`() {
        val mockEngine = MockNativeEngine()
        val mockLauncher = object : MockProcessLauncher() {
            override fun createTempDirectory(prefix: String, vararg attrs: FileAttribute<*>): Path {
                // Return an excessively long path that exceeds 107 bytes when suffix and /supervisor.sock is appended
                return java.nio.file.Paths.get("/" + "a".repeat(120))
            }

            override fun createTempDirectory(dir: Path, prefix: String, vararg attrs: FileAttribute<*>): Path {
                return java.nio.file.Paths.get("/tmp/fallback-mock-dir")
            }
        }
        mockLauncher.mockProcess = MockProcess(9999L, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")
        val mockSocket = MockSocketManager()

        val manager = SupervisorDaemonManager(mockEngine, mockSocket, mockLauncher)
        val context = manager.getOrSpawnSharedDaemon()

        try {
            assertNotNull(context)
            val pathBytes = context.socketPath.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            assertTrue(pathBytes.size < 108, "Fallback path should be under 108 bytes: ${context.socketPath}")
            assertEquals("/tmp/fallback-mock-dir/supervisor.sock", context.socketPath)
        } finally {
            manager.stop()
        }
    }

}
