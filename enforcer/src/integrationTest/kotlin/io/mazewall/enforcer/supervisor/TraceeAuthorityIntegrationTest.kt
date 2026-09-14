package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.NeedsFreshJvm
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.Deadline
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.process.JavaAgentSelection
import io.mazewall.platform.process.JvmChildProcess
import io.mazewall.platform.process.JvmChildSpec
import io.mazewall.platform.seccomp.SeccompNotification
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Arch
import io.mazewall.testing.awaitCondition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@NeedsFreshJvm
class TraceeAuthorityIntegrationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `helper reads pinned tracee memory and cwd then opens captured directory after rename`() {
        val cwd = Files.createDirectory(directory.resolve("cwd"))
        Files.writeString(cwd.resolve("target"), "authority contents")
        probe(cwd, "target").use { tracee ->
            SocketHelperPool(1, 1, SocketHelperProcess::start).use { pool ->
                pool.session().use { session ->
                    val job = tracee.open()
                    val result = session.submitAuthority(job)
                    val prepared = job.prepared.get(10, TimeUnit.SECONDS)
                    assertEquals("target", prepared.path)
                    assertEquals(cwd.resolve("target").toString(), prepared.resolvedPath)
                    assertEquals(ProcessHandle.current().pid().toInt(), prepared.ppid)
                    Files.move(cwd, directory.resolve("moved"))
                    job.decision.complete(TraceeAuthorityDecision(SupervisorRoute.InjectFd, null))
                    val effect = authority(result)
                    val opened = assertInstanceOf(SupervisorReplyEffect.AddFdAndReply::class.java, effect)
                    opened.descriptor.use {
                        assertEquals("authority contents", Files.readString(Path.of("/proc/self/fd/${it.handle.value}")))
                    }
                    assertEquals(524288, opened.flags.value)
                }
            }
        }
    }

    @Test
    fun `blocked authority open is killed and reaped before a replacement serves another request`() {
        val mkfifo = ProcessBuilder("mkfifo", directory.resolve("fifo").toString()).start()
        assertTrue(mkfifo.waitFor(5, TimeUnit.SECONDS))
        assertEquals(0, mkfifo.exitValue())
        val pid = AtomicLong()
        probe(directory, "fifo").use { tracee ->
            SocketHelperPool(1, 1) { startHelper(pid) }.use { pool ->
                val session = pool.session()
                val job = tracee.open()
                val result = session.submitAuthority(job)
                job.prepared.get(10, TimeUnit.SECONDS)
                job.decision.complete(TraceeAuthorityDecision(SupervisorRoute.InjectFd, null))
                awaitBlockedFifo(pid.get())
                val before = System.nanoTime()
                session.cancel()
                assertTrue(System.nanoTime() - before < TimeUnit.MILLISECONDS.toNanos(500), "reactor cancellation must not join reaping")
                session.close()
                assertTrue(result.isCancelled)
                assertFalse(ProcessHandle.of(pid.get()).map { it.isAlive }.orElse(false))
                val replacement = tracee.open().also { it.decision.complete(TraceeAuthorityDecision(SupervisorRoute.Abort(13, "test deny"), null)) }
                pool.session().use { next ->
                    assertEquals(SupervisorReplyEffect.Error(13), authority(next.submitAuthority(replacement)))
                }
            }
        }
    }

    @Test
    fun `helper death returns EIO and retires the process without granting another reply`() {
        val pid = AtomicLong()
        probe(directory, "missing").use { tracee ->
            SocketHelperPool(1, 1) { startHelper(pid) }.use { pool ->
                pool.session().use { session ->
                    val job = tracee.open()
                    val result = session.submitAuthority(job)
                    job.prepared.get(10, TimeUnit.SECONDS)
                    ProcessHandle.of(pid.get()).orElseThrow().destroyForcibly()
                    awaitCondition(description = "authority helper death") { !ProcessHandle.of(pid.get()).map { it.isAlive }.orElse(false) }
                    job.decision.complete(TraceeAuthorityDecision(SupervisorRoute.InjectFd, null))
                    assertEquals(SocketHelperResult.Failed(5), result.get(10, TimeUnit.SECONDS))
                }
            }
        }
    }

    @Test
    fun `authorization timeout kills the waiting helper within the original request budget`() {
        val pid = AtomicLong()
        probe(directory, "missing").use { tracee ->
            SocketHelperPool(1, 1) { startHelper(pid) }.use { pool ->
                pool.session().use { session ->
                    val job = tracee.open(Deadline.afterMillis(1500))
                    val result = session.submitAuthority(job)
                    job.prepared.get(10, TimeUnit.SECONDS)
                    assertEquals(SocketHelperResult.Failed(5), result.get(10, TimeUnit.SECONDS))
                    assertFalse(ProcessHandle.of(pid.get()).map { it.isAlive }.orElse(false))
                }
            }
        }
    }

    @Test
    fun `secure exec captures target and read-only NUL in the helper preserving vector registers`() {
        probe(directory, "/bin/true").use { tracee ->
            SocketHelperPool(1, 1, SocketHelperProcess::start).use { pool ->
                pool.session().use { session ->
                    val arch = Arch.current()
                    val notification = SeccompNotification(46, tracee.tid, arch.audit, arch.execve, longArrayOf(tracee.address, 0x1234, 0x5678, 0, 0, 0))
                    val job = TraceeAuthorityJob(notification, Deadline.afterMillis(30000), emptyList())
                    val result = session.submitAuthority(job)
                    assertEquals(SupervisorRoute.AskJvm, job.prepared.get(10, TimeUnit.SECONDS).route)
                    job.decision.complete(TraceeAuthorityDecision(SupervisorRoute.SecureExec, null))
                    val effect = assertInstanceOf(SupervisorReplyEffect.SecureExecAndReply::class.java, authority(result))
                    assertEquals(0x1234L, effect.request.argv)
                    assertEquals(0x5678L, effect.request.envp)
                    assertTrue(checkNotNull(effect.capturedEmptyPath) > 0)
                    val target = assertInstanceOf(SupervisorDescriptorResult.Opened::class.java, effect.target)
                    target.descriptor.use {
                        assertEquals(Path.of("/bin/true").toRealPath(), Path.of("/proc/self/fd/${it.handle.value}").toRealPath())
                    }
                }
            }
        }
    }

    private fun authority(result: CompletableFuture<SocketHelperResult>): SupervisorReplyEffect = assertInstanceOf(SocketHelperResult.Authority::class.java, result.get(10, TimeUnit.SECONDS)).effect

    @Test
    fun `invalid relative open dirfd cannot select the captured root capability`() = invalidDirfdCannotSelectRoot(exec = false)

    @Test
    fun `invalid relative secure exec dirfd cannot select the captured root capability`() = invalidDirfdCannotSelectRoot(exec = true)

    private fun invalidDirfdCannotSelectRoot(exec: Boolean) {
        probe(directory, "bin/true").use { tracee ->
            SocketHelperPool(1, 1, SocketHelperProcess::start).use { pool ->
                for (dirfd in listOf(Int.MIN_VALUE, Int.MIN_VALUE + 1, -101, -1)) {
                    pool.session().use { session ->
                        val arch = Arch.current()
                        val args = if (exec) longArrayOf(dirfd.toLong(), tracee.address, 0x1234, 0x5678, 0, 0) else longArrayOf(dirfd.toLong(), tracee.address, 524288, 0, 0, 0)
                        val notification = SeccompNotification(47, tracee.tid, arch.audit, if (exec) arch.execveat else arch.openat, args)
                        val job = TraceeAuthorityJob(notification, Deadline.afterMillis(30000), emptyList())
                        val result = session.submitAuthority(job)
                        job.prepared.get(10, TimeUnit.SECONDS)
                        job.decision.complete(TraceeAuthorityDecision(if (exec) SupervisorRoute.SecureExec else SupervisorRoute.InjectFd, null))
                        val effect = authority(result)
                        SupervisorReplyHandoff(effect).use {
                            assertEquals(SupervisorReplyEffect.Error(9), effect, "invalid relative dirfd $dirfd must return EBADF")
                        }
                    }
                }
            }
        }
    }

    private fun startHelper(pid: AtomicLong): SocketHelperProcess {
        val before = ProcessHandle.current().children().use { children -> children.map { it.pid() }.toList().toSet() }
        return SocketHelperProcess.start().also {
            pid.set(
                ProcessHandle.current().children().use { children ->
                children
                .filter { it.pid() !in before }
                .findFirst()
                .orElseThrow()
                .pid()
            },
            )
        }
    }

    private fun probe(
        cwd: Path,
        path: String,
    ): Probe {
        val spec = JvmChildSpec(TraceeAuthorityProbeChild::class.java.name, listOf(ProcessHandle.current().pid().toString(), path), javaAgents = JavaAgentSelection.None)
        val process = ProcessBuilder(JvmChildProcess.commandLine(spec)).directory(cwd.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val values = checkNotNull(process.inputStream.bufferedReader().readLine()).split(' ')
        return Probe(process, values[0].toInt(), values[1].toLong())
    }

    private class Probe(
        val process: Process,
        val tid: Int,
        val address: Long,
    ) : AutoCloseable {
        fun open(deadline: Deadline = Deadline.afterMillis(30000)): TraceeAuthorityJob =
            TraceeAuthorityJob(SeccompNotification(42, tid, Arch.current().audit, Arch.current().openat, longArrayOf(-100, address, 524288, 0, 0, 0)), deadline, emptyList())

        override fun close() {
            process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        }
    }

    private fun awaitBlockedFifo(pid: Long) {
        awaitCondition(description = "authority helper enters blocking FIFO open") {
            Files.list(Path.of("/proc/$pid/task")).use { tasks ->
                tasks.anyMatch { task ->
                try {
                    Files.readString(task.resolve("wchan")).trim() == "wait_for_partner"
                } catch (_: java.nio.file.NoSuchFileException) {
                    false
                }
            }
            }
        }
    }
}
