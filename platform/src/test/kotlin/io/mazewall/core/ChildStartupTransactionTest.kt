package io.mazewall.platform.lifecycle

import io.mazewall.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.MockProcess
import io.mazewall.enforcer.api.MockProcessLauncher
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class ChildStartupTransactionTest {
    @Test
    fun `failed pre-retirement callback still reaps the child and preserves fatal identity`() {
        val fatal = LinkageError("application retirement failed")
        val cleanupFailure = java.io.IOException("endpoint retirement failed")
        var deletes = 0
        val process = MockProcess(123, "READY\n")
        val launcher = object : MockProcessLauncher(process) {
            override fun deleteIfExists(path: Path): Boolean {
                if (++deletes == 1) throw cleanupFailure
                return true
            }
        }
        val startup = ChildStartupTransaction(launcher, "test-", "child.sock")
        startup.start(JvmChildSpec("test.Main"))
        startup.startOutputPump("READY", {}, "test-retirement-pump")
        startup.registerShutdownHook { throw fatal }
        startup.awaitReady(1)
        val owner = startup.commit()
        assertSame(fatal, assertThrows(LinkageError::class.java) { owner.shutdownHook.run() })
        assertFalse(process.isAlive)
        assertTrue(fatal.suppressed.contains(cleanupFailure))
        owner.close()
        assertTrue(launcher.shutdownHooks.isEmpty())
    }

    @Test
    fun `startup boundary rejects uncommitted success and retires its endpoint`() {
        val deleted = mutableListOf<Path>()
        val launcher = object : MockProcessLauncher() {
            override fun deleteIfExists(path: Path): Boolean = deleted.add(path)
        }
        val startup = ChildStartupTransaction(launcher, "test-", "child.sock")
        assertThrows(IllegalStateException::class.java) { startup.runStartup<String> { "not committed" } }
        assertEquals(2, deleted.size)
    }

    @Test
    fun `startup fatal identity survives ordinary rollback failure`() {
        val fatal = LinkageError("launch")
        val ordinary = java.io.IOException("socket cleanup")
        var deletes = 0
        val launcher = object : MockProcessLauncher() {
            override fun deleteIfExists(path: Path): Boolean {
                if (++deletes == 1) throw ordinary
                return true
            }
        }
        val startup = ChildStartupTransaction(launcher, "test-", "child.sock")
        assertSame(fatal, assertThrows(LinkageError::class.java) { startup.runStartup<Nothing> { throw fatal } })
        assertSame(ordinary, fatal.suppressed.single())
        assertEquals(2, deletes)
        startup.close()
        assertEquals(4, deletes)
    }

    @Test
    fun `startup observes caller interrupt and rollback restores consumed interruption`() {
        val interruption = InterruptedException("startup cancelled")
        var deletes = 0
        val launcher = object : MockProcessLauncher() {
            override fun deleteIfExists(path: Path): Boolean {
                assertFalse(Thread.currentThread().isInterrupted, "Cleanup needs an un-interrupted bounded reap")
                deletes++
                return true
            }
        }
        val startup = ChildStartupTransaction(launcher, "test-", "child.sock")
        Thread.currentThread().interrupt()
        try {
            assertSame(
                interruption,
                assertThrows(InterruptedException::class.java) {
                startup.runStartup<Nothing> {
                    assertTrue(Thread.interrupted(), "Startup must observe pre-existing cancellation")
                    throw interruption
                }
            },
            )
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(2, deletes)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `endpoint close retains fatal error and attempts directory deletion`() {
        val fatal = LinkageError("socket deletion")
        val later = java.io.IOException("directory deletion")
        var deletes = 0
        val launcher = object : MockProcessLauncher() {
            override fun deleteIfExists(path: Path): Boolean {
                deletes++
                if (deletes == 1) throw fatal
                throw later
            }
        }
        val endpoint = PrivateUnixEndpoint.create(launcher, "test-", "child.sock")
        assertSame(fatal, assertThrows(LinkageError::class.java) { endpoint.close() })
        assertEquals(2, deletes)
        assertTrue(fatal.suppressed.contains(later))
    }

    @Test
    fun `failed endpoint retirement retries without destroying or reaping child twice`() {
        var destroys = 0
        var reaps = 0
        var deletes = 0
        val process = object : MockProcess(123, "READY\n") {
            override fun destroy() {
                destroys++
                super.destroy()
            }

            override fun waitFor(
                timeout: Long,
                unit: TimeUnit,
            ): Boolean {
                reaps++
                return !isAlive
            }
        }
        val launcher = object : MockProcessLauncher(process) {
            override fun deleteIfExists(path: Path): Boolean {
                if (++deletes == 1) throw java.io.IOException("transient deletion failure")
                return true
            }
        }
        val startup = ChildStartupTransaction(launcher, "test-", "child.sock")
        startup.start(JvmChildSpec("test.Main"))
        startup.startOutputPump("READY", {}, "test-retry-pump")
        startup.registerShutdownHook()
        startup.awaitReady(1)
        val owner = startup.commit()
        assertThrows(java.io.IOException::class.java) { owner.close() }
        owner.close()
        assertEquals(4, deletes)
        assertEquals(1, destroys)
        assertEquals(1, reaps)
        assertTrue(launcher.shutdownHooks.isEmpty())
    }

    @Test
    fun `invalid fallback endpoint retires both acquired directories`() {
        val deleted = mutableListOf<Path>()
        val first = Path.of("/" + "a".repeat(120))
        val second = Path.of("/" + "b".repeat(120))
        val launcher = object : MockProcessLauncher() {
            override fun createTempDirectory(
                prefix: String,
                vararg attrs: java.nio.file.attribute.FileAttribute<*>,
            ): Path = first

            override fun createTempDirectory(
                dir: Path,
                prefix: String,
                vararg attrs: java.nio.file.attribute.FileAttribute<*>,
            ): Path = second

            override fun deleteIfExists(path: Path): Boolean = deleted.add(path)
        }
        assertThrows(IllegalArgumentException::class.java) { PrivateUnixEndpoint.create(launcher, "test-", "child.sock") }
        assertEquals(listOf(first.resolve("child.sock"), first, second.resolve("child.sock"), second), deleted)
    }

    @Test
    fun `cleanup attempts all releases and fatal cleanup failure dominates ordinary failure`() {
        val calls = mutableListOf<String>()
        val ordinary = SecurityException("hook removal")
        val fatal = LinkageError("process destruction")
        val process = object : MockProcess(123, "READY\n") {
            override fun destroyForcibly(): Process {
                calls.add("destroy")
                setAlive(false)
                throw fatal
            }

            override fun waitFor(
                timeout: Long,
                unit: TimeUnit,
            ): Boolean {
                calls.add("reap")
                return true
            }
        }
        val launcher = object : MockProcessLauncher(process) {
            override fun removeShutdownHook(hook: Thread) {
                calls.add("hook")
                super.removeShutdownHook(hook)
                if (calls.count { it == "hook" } == 1) throw ordinary
            }

            override fun deleteIfExists(path: Path): Boolean {
                calls.add(path.fileName.toString())
                return true
            }
        }
        val startup = ChildStartupTransaction(launcher, "test-", "child.sock")
        startup.start(JvmChildSpec("test.Main"))
        startup.startOutputPump("READY", {}, "test-cleanup-failure-pump")
        startup.registerShutdownHook()
        assertSame(fatal, assertThrows(LinkageError::class.java) { startup.close() })
        assertTrue(fatal.suppressed.contains(ordinary))
        assertEquals(listOf("hook", "destroy", "reap", "child.sock", "mock-dir"), calls)
        startup.close()
        startup.close()
        assertEquals(6, calls.size)
    }

    @Test
    fun `interrupted startup still cleans up child and preserves interrupt`() {
        val launcher = MockProcessLauncher(MockProcess(123, "not ready\n"))
        try {
            assertThrows(InterruptedException::class.java) {
                ChildStartupTransaction(launcher, "test-", "child.sock").use { startup ->
                    startup.start(JvmChildSpec("test.Main"))
                    startup.startOutputPump("READY", {}, "test-interrupt-pump")
                    startup.registerShutdownHook()
                    Thread.currentThread().interrupt()
                    startup.awaitReady(30)
                }
            }
            assertTrue(Thread.currentThread().isInterrupted)
            assertFalse(launcher.mockProcess.isAlive)
            assertTrue(launcher.shutdownHooks.isEmpty())
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `uncommitted resources close and reap while committed resources stay alive until retirement`() {
        for (commit in listOf(false, true)) {
            var reaped = 0
            val process = object : MockProcess(123, "READY\n") {
                override fun waitFor(
                    timeout: Long,
                    unit: TimeUnit,
                ): Boolean {
                    reaped++
                    return !isAlive
                }
            }
            val deleted = mutableListOf<Path>()
            val launcher = object : MockProcessLauncher(process) {
                override fun deleteIfExists(path: Path): Boolean = deleted.add(path)
            }
            var owner: ChildProcessResources? = null
            ChildStartupTransaction(launcher, "test-", "child.sock").use { startup ->
                startup.start(JvmChildSpec("test.Main"))
                startup.startOutputPump("READY", {}, "test-owned-pump")
                startup.registerShutdownHook()
                startup.awaitReady(1)
                if (commit) owner = startup.commit()
            }
            assertEquals(commit, process.isAlive)
            owner?.close()
            owner?.close()
            assertFalse(process.isAlive)
            assertTrue(reaped > 0)
            assertTrue(launcher.shutdownHooks.isEmpty())
            assertEquals(listOf(Path.of("/tmp/mock-dir/child.sock"), Path.of("/tmp/mock-dir")), deleted)
        }
    }

    @Test
    fun `EOF without readiness fails promptly and rolls back`() {
        val launcher = MockProcessLauncher(MockProcess(123, "no readiness\n"))
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2)) {
            assertThrows(IllegalStateException::class.java) {
                ChildStartupTransaction(launcher, "test-", "child.sock").use { startup ->
                    startup.start(JvmChildSpec("test.Main"))
                    startup.startOutputPump("READY", {}, "test-eof-pump")
                    startup.registerShutdownHook()
                    startup.awaitReady(30)
                    startup.commit()
                }
            }
        }
        assertFalse(launcher.mockProcess.isAlive)
        assertTrue(launcher.shutdownHooks.isEmpty())
    }
}
