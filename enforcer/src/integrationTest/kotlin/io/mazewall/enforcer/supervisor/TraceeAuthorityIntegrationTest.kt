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
    @TempDir var injectedDirectory: Path? = null
    private val directory: Path get() = requireNotNull(injectedDirectory) { "JUnit temporary directory was not injected" }

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
                    assertEffect(effect) {
                    val opened = assertInstanceOf(SupervisorReplyEffect.AddFdAndReply::class.java, effect)
                    assertEquals("authority contents", Files.readString(Path.of("/proc/self/fd/${opened.descriptor.handle.value}")))
                    assertEquals(524288, opened.flags.value)
                    }
                }
            }
        }
    }

    @Test
    fun `blocked authority open is killed and reaped before a replacement serves another request`() {
        OwnedAcquisition
            .acquireForHandoff(
            acquire = { ProcessBuilder("mkfifo", directory.resolve("fifo").toString()).start() },
            verify = { mkfifo ->
                assertTrue(mkfifo.waitFor(5, TimeUnit.SECONDS))
                assertEquals(0, mkfifo.exitValue())
            },
            rollback = processRollback(),
        ).let { OwnedAcquisition.releaseOwned(it, processRollback()) }
        val pid = AtomicLong()
        val owned = java.util.concurrent.atomic
            .AtomicReference<ProcessHandle>()
        probe(directory, "fifo").use { tracee ->
            SocketHelperPool(1, 1) { startHelper(pid, owned) }.use { pool ->
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
                assertFalse(checkNotNull(owned.get()).isAlive)
                val replacement = tracee.open().also { it.decision.complete(TraceeAuthorityDecision(SupervisorRoute.Abort(13, "test deny"), null)) }
                pool.session().use { next ->
                    val effect = authority(next.submitAuthority(replacement))
                    assertEffect(effect) {
                        assertEquals(SupervisorReplyEffect.Error(13), effect)
                    }
                }
            }
        }
    }

    @Test
    fun `helper death returns EIO and retires the process without granting another reply`() {
        val pid = AtomicLong()
        val owned = java.util.concurrent.atomic
            .AtomicReference<ProcessHandle>()
        probe(directory, "missing").use { tracee ->
            SocketHelperPool(1, 1) { startHelper(pid, owned) }.use { pool ->
                pool.session().use { session ->
                    val job = tracee.open()
                    val result = session.submitAuthority(job)
                    job.prepared.get(10, TimeUnit.SECONDS)
                    checkNotNull(owned.get()).destroyForcibly()
                    awaitCondition(description = "authority helper death") { !checkNotNull(owned.get()).isAlive }
                    job.decision.complete(TraceeAuthorityDecision(SupervisorRoute.InjectFd, null))
                    val received = result.get(10, TimeUnit.SECONDS)
                    assertResult(received) { assertEquals(SocketHelperResult.Failed(5), received) }
                }
            }
        }
    }

    @Test
    fun `authorization timeout kills the waiting helper within the original request budget`() {
        val pid = AtomicLong()
        val owned = java.util.concurrent.atomic
            .AtomicReference<ProcessHandle>()
        probe(directory, "missing").use { tracee ->
            SocketHelperPool(1, 1) { startHelper(pid, owned) }.use { pool ->
                pool.session().use { session ->
                    val job = tracee.open(Deadline.afterMillis(1500))
                    val result = session.submitAuthority(job)
                    job.prepared.get(10, TimeUnit.SECONDS)
                    val received = result.get(10, TimeUnit.SECONDS)
                    assertResult(received) { assertEquals(SocketHelperResult.Failed(5), received) }
                    assertFalse(checkNotNull(owned.get()).isAlive)
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
                    val received = authority(result)
                    assertEffect(received) {
                    val effect = assertInstanceOf(SupervisorReplyEffect.SecureExecAndReply::class.java, received)
                    assertEquals(0x1234L, effect.request.argv)
                    assertEquals(0x5678L, effect.request.envp)
                    assertTrue(checkNotNull(effect.capturedEmptyPath) > 0)
                    val target = assertInstanceOf(SupervisorDescriptorResult.Opened::class.java, effect.target)
                    assertEquals(Path.of("/bin/true").toRealPath(), Path.of("/proc/self/fd/${target.descriptor.handle.value}").toRealPath())
                    }
                }
            }
        }
    }

    private fun authority(result: CompletableFuture<SocketHelperResult>): SupervisorReplyEffect {
        val received = OwnedAcquisition.acquireForHandoff(
            acquire = { result.get(10, TimeUnit.SECONDS) },
            verify = { assertInstanceOf(SocketHelperResult.Authority::class.java, it) },
            rollback = listOf({ it.dispose() }),
        )
        return (received as SocketHelperResult.Authority).effect
    }

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

    private fun startHelper(
        pid: AtomicLong,
        owned: java.util.concurrent.atomic.AtomicReference<ProcessHandle>? = null,
        discover: ((SocketHelperProcess) -> ProcessHandle)? = null,
    ): SocketHelperProcess {
        return OwnedAcquisition.acquireForHandoff(
            acquire = { SocketHelperProcess.start() },
            verify = { helper ->
                val actual = helper.processHandle
                discover?.invoke(helper)
                owned?.set(actual)
                pid.set(actual.pid())
            },
            rollback = listOf({ helper -> helper.close() }),
        )
    }

    private fun probe(
        cwd: Path,
        path: String,
    ): Probe {
        val spec = JvmChildSpec(TraceeAuthorityProbeChild::class.java.name, listOf(ProcessHandle.current().pid().toString(), path), javaAgents = JavaAgentSelection.None)
        return acquireProbe(
            acquire = { ProcessBuilder(JvmChildProcess.commandLine(spec)).directory(cwd.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start() },
        )
    }

    private object OwnedAcquisition {
        // Own the actual acquisition before any discovery, parser, or registration work.
        fun <T> acquireForHandoff(
            acquire: () -> T,
            verify: (T) -> Unit,
            rollback: List<(T) -> Unit>,
        ): T {
            val owned = acquire()
            runCatching { verify(owned) }.exceptionOrNull()?.let { primary ->
                releaseOwned(owned, rollback, primary)
                throw primary
            }
            return owned
        }

        fun <T> releaseOwned(
            owned: T,
            releases: List<(T) -> Unit>,
            primary: Throwable? = null,
        ) {
            var outcome = primary
            var interrupted = Thread.interrupted() || primary is InterruptedException
            try {
                for (release in releases) {
                    try {
                        val failure = attemptOwnedRelease(owned, release)
                        if (failure is InterruptedException) interrupted = true
                        if (failure != null) outcome = CleanupFailures.merge(outcome, failure)
                    } finally {
                        if (Thread.interrupted()) interrupted = true
                    }
                }
                outcome?.let { throw it }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }

        private fun <T> attemptOwnedRelease(
            owned: T,
            release: (T) -> Unit,
        ): Throwable? = runCatching { release(owned) }.exceptionOrNull()

        fun awaitPhysicalEnd(wait: (Long) -> Boolean) {
            var interrupted = Thread.interrupted()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            try {
                while (true) {
                    val remaining = deadline - System.nanoTime()
                    assertTrue(remaining > 0, "Owned resource must physically end within cleanup reserve")
                    try {
                        if (wait(remaining)) return
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }

    private fun acquireProbe(
        acquire: () -> Process,
        readiness: ((Process) -> String?)? = null,
    ): Probe {
        var owner: Probe? = null
        OwnedAcquisition.acquireForHandoff(
            acquire,
            { process ->
            val probe = Probe(process)
            owner = probe
            // One observation deadline, with thirty seconds left in the enclosing 120-second budget.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
            val line = if (readiness == null) {
                probe.readReadiness(deadline)
            } else {
                val ready = CompletableFuture<String?>()
                probe.reader = Thread.ofPlatform().name("tracee-probe-readiness").unstarted {
                    runCatching { readiness(probe.process) }.fold(
                        onSuccess = { ready.complete(it) },
                        onFailure = { failure ->
                            probe.readinessFailure.set(failure)
                            ready.completeExceptionally(failure)
                        },
                    )
                }
                checkNotNull(probe.reader).start()
                try {
                    ready.get(maxOf(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                } catch (failure: java.util.concurrent.ExecutionException) {
                    throw failure.cause ?: failure
                }
            }
            probe.reader?.let(::retireReader)
            val values = checkNotNull(line).split(' ')
            probe.register(values[0].toInt(), values[1].toLong())
        },
            listOf({ process ->
            val probe = owner
            if (probe == null) OwnedAcquisition.releaseOwned(process, processRollback()) else probe.close()
        }),
        )
        return checkNotNull(owner)
    }

    private fun retireReader(reader: Thread) {
        OwnedAcquisition.awaitPhysicalEnd { remaining -> reader.join(java.time.Duration.ofNanos(remaining)) }
    }

    private fun assertEffect(
        effect: SupervisorReplyEffect,
        assertions: () -> Unit,
    ) {
        OwnedAcquisition
            .acquireForHandoff(
            acquire = { SupervisorReplyHandoff(effect) },
            verify = { assertions() },
            rollback = listOf({ owner -> owner.close() }),
        ).close()
    }

    private fun assertResult(
        result: SocketHelperResult,
        assertions: () -> Unit,
    ) {
        if (result is SocketHelperResult.Authority) {
            assertEffect(result.effect, assertions)
        } else {
            OwnedAcquisition
                .acquireForHandoff(
                acquire = { result },
                verify = { assertions() },
                rollback = listOf({ it.dispose() }),
            ).dispose()
        }
    }

    private fun processRollback(): List<(Process) -> Unit> =
        listOf(
        { it.destroyForcibly() },
        { process -> OwnedAcquisition.awaitPhysicalEnd { remaining -> process.waitFor(remaining, TimeUnit.NANOSECONDS) } },
        { it.outputStream.close() },
        { it.inputStream.close() },
        { it.errorStream.close() },
    )

    // Controls always retire their real child independently of the intentionally RED seam.
    private fun retireControl(process: Process) {
        var interrupted = Thread.interrupted()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        try {
            process.destroyForcibly()
            while (process.isAlive) {
                val left = deadline - System.nanoTime()
                assertTrue(left > 0, "Control child must physically end within cleanup reserve")
                try {
                    assertTrue(process.waitFor(left, TimeUnit.NANOSECONDS))
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    @Test
    fun `quality F11 held readiness timeout must retire actual acquired probe`() {
        val child = ProcessBuilder("sleep", "30").start()
        val timeout = IllegalStateException("held readiness exceeded finite observation budget")
        var escaped = false
        try {
            assertSame(
                timeout,
                assertThrows(IllegalStateException::class.java) {
                acquireProbe({ child }) {
                    assertFalse(it.waitFor(100, TimeUnit.MILLISECONDS))
                    throw timeout
                }
            },
            )
            escaped = child.isAlive
        } finally {
            retireControl(child)
        }
        assertFalse(child.isAlive)
        assertFalse(escaped, "Readiness timeout escaped its actual acquired child")
    }

    @Test
    fun `quality F11 readiness EOF rolls back live acquired probe`() = failedProbeReadiness(null)

    @Test
    fun `quality F11 malformed PID rolls back live acquired probe`() = failedProbeReadiness("not-a-pid 4096")

    private fun failedProbeReadiness(line: String?) {
        val child = ProcessBuilder("sleep", "30").start()
        var escaped = false
        try {
            assertThrows(Exception::class.java) {
                acquireProbe({ child }) { process ->
                    assertFalse(process.waitFor(100, TimeUnit.MILLISECONDS), "Readiness failure must occur while actual child is live")
                    line
                }
            }
            escaped = child.isAlive
        } finally {
            retireControl(child)
        }
        assertFalse(child.isAlive)
        assertFalse(escaped, "Readiness or PID parse failure escaped a live child")
    }

    @Test
    fun `quality F11 interrupted probe close reaps before restoring owner interruption`() {
        val child = ProcessBuilder("sleep", "30").start()
        var failure: Throwable? = null
        var alive = false
        var restored = false
        try {
            Thread.currentThread().interrupt()
            failure = runCatching { Probe(child, 0, 0).close() }.exceptionOrNull()
            alive = child.isAlive
            restored = Thread.currentThread().isInterrupted
        } finally {
            retireControl(child)
            Thread.interrupted()
        }
        assertNull(failure, "Close must finish physical retirement despite interruption")
        assertFalse(alive)
        assertTrue(restored)
    }

    @Test
    fun `quality F11 helper discovery failure rolls back exact acquired helper`() {
        val discovery = IllegalArgumentException("identity discovery failed")
        var owned: SocketHelperProcess? = null
        var escaped = false
        try {
            assertSame(
                discovery,
                assertThrows(IllegalArgumentException::class.java) {
                startHelper(AtomicLong()) { helper ->
                    owned = helper
                    throw discovery
                }
            },
            )
            escaped = checkNotNull(owned).processHandle.isAlive
        } finally {
            owned?.close()
        }
        assertFalse(checkNotNull(owned).processHandle.isAlive)
        assertFalse(escaped, "Discovery failure escaped actual helper")
    }

    @Test
    fun `quality F11 interrupted readiness retires before restoring acquisition owner flag`() {
        val child = ProcessBuilder("sleep", "30").start()
        var escaped = false
        var restored = false
        var cleanupInterrupted = false
        try {
            assertThrows(InterruptedException::class.java) {
                OwnedAcquisition.acquireForHandoff(
                    { child },
                    {
                    Thread.currentThread().interrupt()
                    it.waitFor(5, TimeUnit.SECONDS)
                },
                    listOf<(Process) -> Unit>(
                    { cleanupInterrupted = Thread.currentThread().isInterrupted },
                    { retireControl(it) },
                ) +
                    processRollback().drop(1),
                )
            }
            escaped = child.isAlive
            restored = Thread.currentThread().isInterrupted
        } finally {
            retireControl(child)
            Thread.interrupted()
        }
        assertFalse(child.isAlive)
        assertFalse(escaped, "Interrupted acquisition escaped a live child")
        assertFalse(cleanupInterrupted, "Interrupt must remain deferred during rollback")
        assertTrue(restored, "Acquisition owner must regain interruption after physical retirement")
    }

    @Test
    fun `quality F11 helper identity cannot be replaced by unrelated child discovery`() {
        val decoy = ProcessBuilder("sleep", "30").start()
        try {
            val pid = AtomicLong()
            startHelper(pid) { decoy.toHandle() }.use { helper ->
                assertEquals(helper.processHandle.pid(), pid.get(), "Only actual acquired helper may supply identity")
            }
        } finally {
            retireControl(decoy)
        }
        assertFalse(decoy.isAlive)
    }

    @Test
    fun `quality F11 rollback attempts all resources and promotes original late fatal`() {
        val child = ProcessBuilder("sleep", "30").start()
        val primary = IllegalArgumentException("registration failure")
        val fatal = LinkageError("late stream cleanup fatal")
        var attempts = 0
        var observed: Throwable? = null
        var escaped = false
        try {
            observed = runCatching {
                OwnedAcquisition.acquireForHandoff(
                    { child },
                    { throw primary },
                    listOf<(Process) -> Unit>(
                    {
                        attempts++
                        it.outputStream.close()
                        throw fatal
                    },
                    {
                        attempts++
                        retireControl(it)
                    },
                    {
                        attempts++
                        it.inputStream.close()
                    },
                    {
                        attempts++
                        it.errorStream.close()
                    },
                ),
                )
            }.exceptionOrNull()
            escaped = child.isAlive
        } finally {
            retireControl(child)
        }
        assertSame(fatal, observed)
        assertEquals(4, attempts)
        assertFalse(escaped)
        assertFalse(child.isAlive)
    }

    @Test
    fun `quality F11 fatal readiness retains identity after physical rollback`() {
        val child = ProcessBuilder("sleep", "30").start()
        val fatal = LinkageError("readiness fatal")
        var escaped = false
        try {
            assertSame(
                fatal,
                assertThrows(LinkageError::class.java) {
                acquireProbe({ child }) { throw fatal }
            },
            )
            escaped = child.isAlive
        } finally {
            retireControl(child)
        }
        assertFalse(child.isAlive)
        assertFalse(escaped)
    }

    @Test
    fun `quality F11 effect assertion failure disposes whole actual descriptor owner`() {
            SocketHelperPool(1, 1, SocketHelperProcess::start).use { pool ->
                pool.session().use { session ->
                    Files.writeString(directory.resolve("owned"), "owned")
                    probe(directory, "owned").use { readable ->
                        val job = readable.open()
                        val pending = session.submitAuthority(job)
                        job.prepared.get(10, TimeUnit.SECONDS)
                        job.decision.complete(TraceeAuthorityDecision(SupervisorRoute.InjectFd, null))
                        val effect = authority(pending)
                        var opened: SupervisorReplyEffect.AddFdAndReply? = null
                        val failure = AssertionError("positive assertion failed before descriptor use")
                        var escaped = false
                        assertSame(
                            failure,
                            assertThrows(AssertionError::class.java) {
                                assertEffect(effect) {
                                    opened = assertInstanceOf(SupervisorReplyEffect.AddFdAndReply::class.java, effect)
                                    throw failure
                                }
                            },
                        )
                        escaped = checkNotNull(opened).descriptor.handle.isLiveForIo()
                        assertFalse(checkNotNull(opened).descriptor.handle.isLiveForIo())
                        assertFalse(escaped, "Assertion failure escaped whole descriptor-bearing effect")
                    }
                }
            }
    }

    @Test
    fun `quality F11 secure exec assertion failure disposes whole actual target owner`() {
        probe(directory, "/bin/true").use { tracee ->
            SocketHelperPool(1, 1, SocketHelperProcess::start).use { pool ->
                pool.session().use { session ->
                    val arch = Arch.current()
                    val notification = SeccompNotification(46, tracee.tid, arch.audit, arch.execve, longArrayOf(tracee.address, 0x1234, 0x5678, 0, 0, 0))
                    val job = TraceeAuthorityJob(notification, Deadline.afterMillis(30000), emptyList())
                    val pending = session.submitAuthority(job)
                    job.prepared.get(10, TimeUnit.SECONDS)
                    job.decision.complete(TraceeAuthorityDecision(SupervisorRoute.SecureExec, null))
                    val effect = authority(pending)
                    var target: SupervisorDescriptorResult.Opened? = null
                    val failure = AssertionError("secure exec assertion failed before target use")
                    var escaped = false
                    assertSame(
                        failure,
                        assertThrows(AssertionError::class.java) {
                            assertEffect(effect) {
                                val secure = assertInstanceOf(SupervisorReplyEffect.SecureExecAndReply::class.java, effect)
                                target = assertInstanceOf(SupervisorDescriptorResult.Opened::class.java, secure.target)
                                throw failure
                            }
                        },
                    )
                    escaped = checkNotNull(target).descriptor.handle.isLiveForIo()
                    assertFalse(checkNotNull(target).descriptor.handle.isLiveForIo())
                    assertFalse(escaped, "Assertion failure escaped secure exec target owner")
                }
            }
        }
    }

    private inner class Probe(
        val process: Process,
        tid: Int? = null,
        address: Long? = null,
    ) : AutoCloseable {
        private var identity: Pair<Int, Long>? = if (tid != null && address != null) tid to address else null
        val tid: Int get() = checkNotNull(identity).first
        val address: Long get() = checkNotNull(identity).second
        var reader: Thread? = null
        val readinessFailure = java.util.concurrent.atomic
            .AtomicReference<Throwable>()

        fun register(
            tid: Int,
            address: Long,
        ) {
            identity = tid to address
        }

        // Read only available bytes: no readLine task can outlive native readiness.
        fun readReadiness(deadline: Long): String? {
            val input = process.inputStream
            val line = StringBuilder()
            while (true) {
                check(System.nanoTime() < deadline) { "Probe readiness exceeded finite observation budget" }
                if (input.available() > 0) {
                    val value = input.read()
                    if (value == -1) return line.toString().takeIf { it.isNotEmpty() }
                    if (value == '\n'.code) return line.toString().removeSuffix("\r")
                    line.append(value.toChar())
                } else if (!process.isAlive) {
                    return line.toString().removeSuffix("\r").takeIf { it.isNotEmpty() }
                } else {
                    TimeUnit.NANOSECONDS.sleep(minOf(TimeUnit.MILLISECONDS.toNanos(1), maxOf(1L, deadline - System.nanoTime())))
                }
            }
        }

        fun open(deadline: Deadline = Deadline.afterMillis(30000)): TraceeAuthorityJob =
            TraceeAuthorityJob(SeccompNotification(42, tid, Arch.current().audit, Arch.current().openat, longArrayOf(-100, address, 524288, 0, 0, 0)), deadline, emptyList())

        override fun close() {
            OwnedAcquisition.releaseOwned(
                process,
                processRollback().take(2) +
                    listOf<(Process) -> Unit>(
                        { reader?.interrupt() },
                        { reader?.let(::retireReader) },
                        { readinessFailure.get()?.let { throw it } },
                    ) +
                    processRollback().drop(2),
            )
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
