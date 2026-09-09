package io.mazewall.profiler.internal

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeProcess
import io.mazewall.MockProcess
import io.mazewall.MockProcessLauncher
import io.mazewall.MockSocketManager
import io.mazewall.core.PrctlCommand
import io.mazewall.enforcer.supervisor.SupervisorDaemonManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Path

class DaemonStartupRollbackTest {
    @Test
    fun `failed dead-profiler retirement remains owned until retry succeeds`() {
        val sentinel = io.mazewall.profiler.engine.DAEMON_READY_SENTINEL
        val first = MockProcess(123, "$sentinel\n")
        val second = MockProcess(124, "$sentinel\n")
        var starts = 0
        var deletes = 0
        val launcher = object : MockProcessLauncher(first) {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
            ): Process = if (starts++ == 0) first else second

            override fun deleteIfExists(path: Path): Boolean {
                if (++deletes == 1) throw IOException("transient endpoint cleanup")
                return true
            }
        }
        val manager = ProfilerDaemonManager(MockNativeEngine(), MockSocketManager(), launcher)
        manager.getOrSpawnSharedDaemon()
        first.setAlive(false)
        assertThrows(IOException::class.java) { manager.getOrSpawnSharedDaemon() }
        assertEquals(1, starts)
        try {
            assertSame(second, manager.getOrSpawnSharedDaemon().daemonProcess)
            assertEquals(4, deletes)
        } finally {
            manager.stop()
        }
    }

    @Test
    fun `ptracer hook and readiness failures roll back each acquired resource`() {
        for (supervisor in listOf(false, true)) {
            for (stage in listOf("ptracer", "hook", "readiness")) {
                val sentinel = if (supervisor) {
                    io.mazewall.enforcer.supervisor.SupervisorDaemon.DAEMON_READY_SENTINEL
                } else {
                    io.mazewall.profiler.engine.DAEMON_READY_SENTINEL
                }
                val process = MockProcess(123, if (stage == "readiness") "not ready\n" else "$sentinel\n")
                val deleted = mutableListOf<Path>()
                val launcher = object : MockProcessLauncher(process) {
                    override fun addShutdownHook(hook: Thread) {
                        if (stage == "hook") throw SecurityException("hook refused")
                        super.addShutdownHook(hook)
                    }

                    override fun deleteIfExists(path: Path): Boolean = deleted.add(path)
                }
                val engine = MockNativeEngine()
                if (stage == "ptracer") engine.process.prctlResult = LinuxNative.SyscallResult.Error(13, -1L)
                val start = starter(supervisor, engine, launcher)
                assertTimeoutPreemptively(java.time.Duration.ofSeconds(3)) {
                    assertThrows(Exception::class.java, start)
                }
                assertFalse(process.isAlive, "$supervisor $stage leaked child")
                assertTrue(launcher.shutdownHooks.isEmpty(), "$supervisor $stage leaked hook")
                assertEquals(2, deleted.size, "$supervisor $stage leaked endpoint")
            }
        }
    }

    @Test
    fun `dead profiler resources retire before replacement launch`() {
        val sentinel = io.mazewall.profiler.engine.DAEMON_READY_SENTINEL
        val first = MockProcess(123, "$sentinel\n")
        val second = MockProcess(124, "$sentinel\n")
        val deleted = mutableListOf<Path>()
        val launcher = object : MockProcessLauncher(first) {
            var starts = 0

            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
            ): Process {
                if (starts++ > 0) {
                    assertTrue(shutdownHooks.isEmpty(), "Old hook must retire before spawning")
                    assertEquals(2, deleted.size, "Old endpoint must retire before spawning")
                    return second
                }
                return first
            }

            override fun deleteIfExists(path: Path): Boolean = deleted.add(path)
        }
        val manager = ProfilerDaemonManager(MockNativeEngine(), MockSocketManager(), launcher)
        manager.getOrSpawnSharedDaemon()
        first.setAlive(false)
        try {
            assertSame(second, manager.getOrSpawnSharedDaemon().daemonProcess)
            assertEquals(1, launcher.shutdownHooks.size)
        } finally {
            manager.stop()
        }
        assertTrue(launcher.shutdownHooks.isEmpty())
        assertFalse(second.isAlive)
    }

    @Test
    fun `launch failure retires endpoint in both managers`() {
        for (supervisor in listOf(false, true)) {
            val deleted = mutableListOf<Path>()
            val failure = IOException("launch failed")
            val launcher = object : MockProcessLauncher() {
                override fun startProcess(
                    args: List<String>,
                    redirectErrorStream: Boolean,
                ): Process = throw failure

                override fun deleteIfExists(path: Path): Boolean = deleted.add(path)
            }
            val start = starter(supervisor, MockNativeEngine(), launcher)
            assertSame(failure, assertThrows(IOException::class.java, start))
            assertEquals(2, deleted.size, "Both socket path and acquired directory must be retired")
            assertEquals(Path.of("/tmp/mock-dir"), deleted.last())
            assertTrue(launcher.shutdownHooks.isEmpty())
        }
    }

    private fun starter(
        supervisor: Boolean,
        engine: MockNativeEngine,
        launcher: MockProcessLauncher,
    ): () -> Unit =
        if (supervisor) {
            val manager = SupervisorDaemonManager(engine, MockSocketManager(), launcher)
            val start: () -> Unit = {
                manager.getOrSpawnSharedDaemon()
                Unit
            }
            start
        } else {
            val manager = ProfilerDaemonManager(engine, MockSocketManager(), launcher)
            val start: () -> Unit = {
                manager.getOrSpawnSharedDaemon()
                Unit
            }
            start
        }
}
