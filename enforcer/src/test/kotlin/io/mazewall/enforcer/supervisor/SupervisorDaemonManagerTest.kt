package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.api.MockProcess
import io.mazewall.enforcer.api.MockProcessLauncher
import io.mazewall.enforcer.api.MockSocketManager
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.readByte
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.process.ProcessLauncher
import io.mazewall.platform.socket.*
import io.mazewall.platform.socket.SocketManager
import io.mazewall.platform.syscall.*
import io.mazewall.seccomp.SeccompInstallationState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SupervisorDaemonManagerTest {
    @Test
    fun `default shutdown transport remains bound to lifecycle owner`() {
        val owner = MockNativeEngine()
        val ownedOpens = AtomicInteger()
        val ownedCloses = AtomicInteger()
        owner.networking.onSocket = { _, _, _ ->
            ownedOpens.incrementAndGet()
            LinuxNative.SyscallResult.Success(1000L)
        }
        owner.fileSystem.onClose = {
            ownedCloses.incrementAndGet()
            LinuxNative.SyscallResult.Success(0L)
        }
        val manager = SupervisorDaemonManager(
            engine = owner,
            processLauncher = MockProcessLauncher(MockProcess(9999L, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")),
        )
        val foreignCalls = AtomicInteger()
        val foreign = MockNativeEngine().apply {
            networking.onSocket = { _, _, _ ->
                foreignCalls.incrementAndGet()
                LinuxNative.SyscallResult.Error(13, -1L)
            }
        }
        try {
            manager.getOrSpawnSharedDaemon()
            LinuxNative.setEngine(foreign)
            manager.stop()
            assertEquals(0, foreignCalls.get())
            assertEquals(1, ownedOpens.get())
            assertEquals(1, ownedCloses.get())
        } finally {
            manager.stop()
            LinuxNative.resetToDefault()
        }
    }

    @Test
    fun `default lifecycle owner survives global replacement before startup`() {
        val owner = MockNativeEngine()
        LinuxNative.setEngine(owner)
        val manager = SupervisorDaemonManager(
            socketManager = MockSocketManager(),
            processLauncher = MockProcessLauncher(MockProcess(9999L, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")),
        )
        val foreignCalls = AtomicInteger()
        LinuxNative.setEngine(
            MockNativeEngine().apply {
            process.onPrctl = {
                foreignCalls.incrementAndGet()
                LinuxNative.SyscallResult.Error(1, -1L)
            }
        },
        )
        try {
            assertEquals(9999L, manager.getOrSpawnSharedDaemon().daemonProcess.pid())
            manager.stop()
            assertEquals(0, foreignCalls.get())
        } finally {
            manager.stop()
            LinuxNative.resetToDefault()
        }
    }

    @Test
    fun `cached daemon reacquires ptrace authority after another daemon took it`() {
        val engine = MockNativeEngine()
        val launcher = MockProcessLauncher(MockProcess(9999L, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n"))
        val manager = SupervisorDaemonManager(engine, MockSocketManager(), launcher)
        var ptracer = 0L
        engine.process.onPrctl = { command ->
            if (command is io.mazewall.platform.syscall.PrctlCommand.SetPtracer) ptracer = command.tracerPid
            LinuxNative.SyscallResult.Success(0L)
        }
        try {
            val original = manager.getOrSpawnSharedDaemon()
            io.mazewall.platform.process.DaemonPtracer
                .authorize(engine, MockProcess(12345L))
            val reused = manager.getOrSpawnSharedDaemon()
            assertEquals(original, reused)
            assertEquals(9999L, ptracer, "cached supervisor must regain authority before use")
        } finally {
            manager.stop()
        }
    }

    @Test
    fun `cached daemon is not returned when restoring ptrace authority fails`() {
        val engine = MockNativeEngine()
        val launcher = MockProcessLauncher(MockProcess(9999L, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n"))
        val manager = SupervisorDaemonManager(engine, MockSocketManager(), launcher)
        manager.getOrSpawnSharedDaemon()
        io.mazewall.platform.process.DaemonPtracer
            .authorize(engine, MockProcess(12345L))
        engine.process.onPrctl = { LinuxNative.SyscallResult.Error(NativeConstants.EPERM, -1L) }
        try {
            val failure = assertThrows<IllegalStateException> { manager.getOrSpawnSharedDaemon() }
            assertTrue(failure.message!!.contains("errno=1"))
        } finally {
            manager.stop()
        }
    }

    @Test
    fun `cached daemon authorizes supplied caller authority without using lifecycle backend`() {
        val owner = MockNativeEngine()
        val launcher = MockProcessLauncher(MockProcess(9999L, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n"))
        val manager = SupervisorDaemonManager(owner, MockSocketManager(), launcher)
        val caller = MockNativeEngine()
        var authorized = 0L
        caller.process.onPrctl = { command ->
            if (command is io.mazewall.platform.syscall.PrctlCommand.SetPtracer) authorized = command.tracerPid
            LinuxNative.SyscallResult.Success(0L)
        }
        try {
            val original = manager.getOrSpawnSharedDaemon()
            owner.process.onPrctl = { LinuxNative.SyscallResult.Error(NativeConstants.EPERM, -1L) }
            val reused = manager.getOrSpawnSharedDaemon(caller)
            assertEquals(original, reused)
            assertEquals(9999L, authorized)
        } finally {
            owner.process.onPrctl = { LinuxNative.SyscallResult.Success(0L) }
            manager.stop()
        }
    }

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
            if (command is io.mazewall.platform.syscall.PrctlCommand.SetPtracer && command.tracerPid == 9999L) {
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
        val output = java.io.PipedInputStream()
        val writer = java.io.PipedOutputStream(output)
        writer.write((SupervisorDaemon.DAEMON_READY_SENTINEL + "\n").toByteArray())
        val exitWaitEntered = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val mockProcess = object : MockProcess(9999L, exitVal = 42) {
            override fun getInputStream(): java.io.InputStream = output

            override fun waitFor(): Int {
                exitWaitEntered.countDown()
                exited.await()
                return 42
            }
        }
        mockLauncher.mockProcess = mockProcess
        val mockSocket = MockSocketManager()

        val manager = SupervisorDaemonManager(mockEngine, mockSocket, mockLauncher)

        val exitLatch = CountDownLatch(1)
        val exitCodeReceived = java.util.concurrent.atomic
            .AtomicInteger(-1)
        manager.onUnexpectedExit = { exitCode ->
            exitCodeReceived.set(exitCode)
            exitLatch.countDown()
        }

        val context = manager.getOrSpawnSharedDaemon()
        assertNotNull(context)
        writer.close()
        try {
            assertTrue(exitWaitEntered.await(2, TimeUnit.SECONDS), "Output EOF must still observe later process death")
        } finally {
            mockProcess.setAlive(false)
            exited.countDown()
        }

        val completed = exitLatch.await(5, TimeUnit.SECONDS)
        assertTrue(completed, "Expected unexpected exit handler to be invoked")
        assertEquals(42, exitCodeReceived.get())
        manager.stop()
    }

    @Test
    fun `dead daemon transitions once and is replaced on the next lookup`() {
        val mockEngine = MockNativeEngine()
        val first = MockProcess(9999L, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")
        val second = MockProcess(10000L, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")
        val mockLauncher = object : MockProcessLauncher(first) {
            var starts = 0

            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
            ): Process {
                starts++
                return super.startProcess(args, redirectErrorStream)
            }
        }
        val manager = SupervisorDaemonManager(mockEngine, MockSocketManager(), mockLauncher)
        val exits = AtomicInteger(0)
        manager.onUnexpectedExit = { exits.incrementAndGet() }

        val initial = manager.getOrSpawnSharedDaemon()
        first.setAlive(false)
        mockLauncher.mockProcess = second

        val replacement = manager.getOrSpawnSharedDaemon()

        assertEquals(9999L, initial.daemonProcess.pid())
        assertEquals(10000L, replacement.daemonProcess.pid())
        assertEquals(2, mockLauncher.starts)
        assertEquals(1, exits.get(), "a dead daemon must transition to Defunct exactly once")
    }

    @Test
    fun `spawnDaemon falls back to short temp directory when default socket path is too long`() {
        val mockEngine = MockNativeEngine()
        val mockLauncher = object : MockProcessLauncher() {
            override fun createTempDirectory(
                prefix: String,
                vararg attrs: FileAttribute<*>,
            ): Path {
                // Return an excessively long path that exceeds 107 bytes when suffix and /supervisor.sock is appended
                return java.nio.file.Paths
                    .get("/" + "a".repeat(120))
            }

            override fun createTempDirectory(
                dir: Path,
                prefix: String,
                vararg attrs: FileAttribute<*>,
            ): Path {
                return java.nio.file.Paths
                    .get("/tmp/fallback-mock-dir")
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
