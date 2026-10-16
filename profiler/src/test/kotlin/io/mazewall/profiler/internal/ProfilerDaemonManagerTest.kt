package io.mazewall.profiler.internal
import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.api.MockProcess
import io.mazewall.enforcer.api.MockProcessLauncher
import io.mazewall.enforcer.api.MockSocketManager
import io.mazewall.enforcer.api.Platform
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.ProcessLauncher
import io.mazewall.platform.socket.SocketManager
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute
import java.util.concurrent.atomic.AtomicInteger

class ProfilerDaemonManagerTest {
    @Test
    @io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
    @org.junit.jupiter.api.Timeout(15)
    fun `actual shutdown hook retains the live daemon until its session lease retires`() {
        val manager = ProfilerDaemonManager()
        val context = manager.getOrSpawnSharedDaemon()
        val lease = context.lifetime.acquire()
        val finished = java.util.concurrent.CompletableFuture<Unit>()
        val shutdown = Thread {
            try {
                context.shutdownHook.run()
                finished.complete(Unit)
            } catch (failure: Throwable) {
                finished.completeExceptionally(failure)
            }
        }
        shutdown.start()
        try {
            io.mazewall.testing.awaitCondition(description = "real child hook closes session admission") {
                try {
                    context.lifetime.acquire().close()
                    false
                } catch (_: IllegalStateException) {
                    true
                }
            }
            assertFalse(finished.isDone, "Child retirement must wait for the acquired session")
            assertTrue(context.daemonProcess.isAlive)
            lease.close()
            finished.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertFalse(context.daemonProcess.isAlive)
        } finally {
            lease.close()
            shutdown.join(5_000)
            manager.stop()
            assertFalse(shutdown.isAlive)
        }
    }

    @Test
    fun `test daemon spawn and stop with mocks`() {
        val mockEngine = MockNativeEngine()
        val mockLauncher = MockProcessLauncher()
        mockLauncher.mockProcess = MockProcess(8888L, io.mazewall.profiler.internal.engine.DAEMON_READY_SENTINEL + "\n")
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
            override fun createTempDirectory(
                prefix: String,
                vararg attrs: FileAttribute<*>,
            ): Path {
                // Return an excessively long path that exceeds 107 bytes when suffix and /profiler.sock is appended
                return java.nio.file.Paths
                    .get("/" + "a".repeat(120))
            }

            override fun createTempDirectory(
                dir: Path,
                prefix: String,
                vararg attrs: FileAttribute<*>,
            ): Path {
                return java.nio.file.Paths
                    .get("/tmp/fallback-mock-profiler-dir")
            }
        }
        mockLauncher.mockProcess = MockProcess(8888L, io.mazewall.profiler.internal.engine.DAEMON_READY_SENTINEL + "\n")
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
