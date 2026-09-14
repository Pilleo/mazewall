package io.mazewall.profiler.internal
import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.api.MockNativeProcess
import io.mazewall.enforcer.api.MockProcess
import io.mazewall.enforcer.api.MockProcessLauncher
import io.mazewall.enforcer.api.MockSocketManager
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.PrctlCommand
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Path

class DaemonStartupRollbackTest {
    @Test
    fun `failed provisional rollback blocks replacement and stop retries retirement in both managers`() {
        for (supervisor in listOf(false, true)) {
            val launchFailure = IOException("launch failed")
            var starts = 0
            var deletes = 0
            var cleanupFails = true
            val launcher = object : MockProcessLauncher() {
                override fun startProcess(
                    args: List<String>,
                    redirectErrorStream: Boolean,
                ): Process {
                    starts++
                    throw launchFailure
                }

                override fun deleteIfExists(path: Path): Boolean {
                    deletes++
                    if (cleanupFails) throw IOException("endpoint still owned")
                    return true
                }
            }
            val manager = driver(supervisor, MockNativeEngine(), launcher)
            assertSame(launchFailure, assertThrows(IOException::class.java, manager.start))
            assertThrows(IOException::class.java, manager.start)
            assertEquals(1, starts, "Replacement must not launch before provisional retirement succeeds")
            assertThrows(IOException::class.java, manager.stop)
            assertEquals(6, deletes, "Start and stop must retry the retained endpoint")
            cleanupFails = false
            manager.stop()
            assertEquals(8, deletes)
            manager.stop()
            assertEquals(8, deletes, "Successful retirement is not repeated")
            assertSame(launchFailure, assertThrows(IOException::class.java, manager.start))
            assertEquals(2, starts, "Replacement is allowed after retirement")
            manager.stop()
        }
    }

    @Test
    fun `fatal rollback failure dominates ordinary launch failure in both managers`() {
        for (supervisor in listOf(false, true)) {
            val launchFailure = IOException("launch failed")
            val fatal = LinkageError("fatal socket retirement")
            val directoryFailure = IOException("directory retirement")
            var deletes = 0
            val launcher = object : MockProcessLauncher() {
                override fun startProcess(
                    args: List<String>,
                    redirectErrorStream: Boolean,
                ): Process = throw launchFailure

                override fun deleteIfExists(path: Path): Boolean =
                    when (++deletes) {
                    1 -> throw fatal
                    2 -> throw directoryFailure
                    else -> true
                }
            }
            val manager = driver(supervisor, MockNativeEngine(), launcher)
            val observed = assertThrows(LinkageError::class.java, manager.start)
            assertSame(fatal, observed)
            assertTrue(observed.suppressed.any { it === launchFailure })
            assertTrue(observed.suppressed.any { it === directoryFailure })
            assertEquals(2, deletes, "Fatal socket failure must not skip directory retirement")
            manager.stop()
            assertEquals(4, deletes, "Fatal rollback retains its provisional owner")
        }
    }

    @Test
    fun `readiness failure retains unreaped child until retry retirement succeeds in both managers`() {
        for (supervisor in listOf(false, true)) {
            var cleanupFails = true
            var destroys = 0
            var starts = 0
            var deletes = 0
            val process = object : MockProcess(123, "not ready\n") {
                override fun destroyForcibly(): Process {
                    destroys++
                    if (cleanupFails) throw IOException("child still owned")
                    return super.destroyForcibly()
                }

                override fun waitFor(
                    timeout: Long,
                    unit: java.util.concurrent.TimeUnit,
                ): Boolean = !isAlive
            }
            val launcher = object : MockProcessLauncher(process) {
                override fun startProcess(
                    args: List<String>,
                    redirectErrorStream: Boolean,
                ): Process {
                    starts++
                    return process
                }

                override fun deleteIfExists(path: Path): Boolean {
                    deletes++
                    return true
                }
            }
            val manager = driver(supervisor, MockNativeEngine(), launcher)
            assertThrows(IllegalStateException::class.java, manager.start)
            assertTrue(process.isAlive)
            assertTrue(launcher.shutdownHooks.isEmpty())
            assertEquals(2, deletes, "Other releases must still be attempted")
            assertThrows(IOException::class.java, manager.start)
            assertEquals(1, starts)
            cleanupFails = false
            manager.stop()
            assertFalse(process.isAlive)
            assertEquals(3, destroys)
            assertEquals(2, deletes, "Already retired endpoint is not touched again")
        }
    }

    @Test
    fun `fatal cleanup dominates readiness failure after all other resources retire in both managers`() {
        for (supervisor in listOf(false, true)) {
            val fatal = LinkageError("fatal process destruction")
            var destroys = 0
            var deletes = 0
            val process = object : MockProcess(123, "not ready\n") {
                override fun destroyForcibly(): Process {
                    destroys++
                    setAlive(false)
                    throw fatal
                }
            }
            val launcher = object : MockProcessLauncher(process) {
                override fun deleteIfExists(path: Path): Boolean {
                    deletes++
                    return true
                }
            }
            val manager = driver(supervisor, MockNativeEngine(), launcher)
            assertSame(fatal, assertThrows(LinkageError::class.java, manager.start))
            assertTrue(fatal.suppressed.any { it is IllegalStateException && it.message!!.contains("readiness") })
            assertFalse(process.isAlive)
            assertTrue(launcher.shutdownHooks.isEmpty())
            assertEquals(2, deletes)
            manager.stop()
            assertEquals(1, destroys, "Confirmed reaping is not repeated after fatal destruction failure")
        }
    }

    @Test
    fun `failed dead-profiler retirement remains owned until retry succeeds`() {
        val sentinel = io.mazewall.profiler.internal.engine.DAEMON_READY_SENTINEL
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
                    supervisorReadySentinel()
                } else {
                    io.mazewall.profiler.internal.engine.DAEMON_READY_SENTINEL
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
        val sentinel = io.mazewall.profiler.internal.engine.DAEMON_READY_SENTINEL
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
    ): () -> Unit = driver(supervisor, engine, launcher).start

    private data class Driver(
        val start: () -> Unit,
        val stop: () -> Unit,
    )

    private fun driver(
        supervisor: Boolean,
        engine: MockNativeEngine,
        launcher: MockProcessLauncher,
    ): Driver =
        if (supervisor) {
            supervisorDriver(engine, launcher)
        } else {
            val manager = ProfilerDaemonManager(engine, MockSocketManager(), launcher)
            val start: () -> Unit = {
                manager.getOrSpawnSharedDaemon()
            }
            Driver(start, manager::stop)
        }

    /**
     * The supervisor daemon is an enforcer implementation detail.  This cross-module
     * regression test intentionally uses reflection so it can exercise rollback behavior
     * without widening the published enforcer API solely for test access.
     */
    private fun supervisorDriver(
        engine: MockNativeEngine,
        launcher: MockProcessLauncher,
    ): Driver {
        val managerClass = Class.forName("io.mazewall.enforcer.internal.supervisor.SupervisorDaemonManager")
        val manager = managerClass.declaredConstructors
            .single { constructor ->
            constructor.parameterTypes.size == 3
        }.newInstance(engine, MockSocketManager(), launcher)
        val start = managerClass.getDeclaredMethod("getOrSpawnSharedDaemon")
        val stop = managerClass.getDeclaredMethod("stop")
        return Driver(
            start = { invokeInternal(start, manager) },
            stop = { invokeInternal(stop, manager) },
        )
    }

    private fun supervisorReadySentinel(): String =
        Class
            .forName("io.mazewall.enforcer.internal.supervisor.SupervisorDaemon")
            .getDeclaredField("DAEMON_READY_SENTINEL")
            .get(null) as String

    private fun invokeInternal(
        method: java.lang.reflect.Method,
        receiver: Any,
    ) {
        try {
            method.invoke(receiver)
        } catch (failure: java.lang.reflect.InvocationTargetException) {
            throw checkNotNull(failure.cause)
        }
    }
}
