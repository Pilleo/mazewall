package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.NeedsFreshJvm
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.OpenFlags
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.testing.awaitCondition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@NeedsFreshJvm
class SupervisorOpenHelperIntegrationTest {
    @TempDir var injectedDirectory: Path? = null
    private val directory: Path get() = requireNotNull(injectedDirectory) { "JUnit temporary directory was not injected" }

    @Test
    fun `open uses transferred directory after rename and returns owned readable descriptor`() {
        val original = Files.createDirectory(directory.resolve("original"))
        Files.writeString(original.resolve("target"), "capability contents")
        directoryFd(original).use { dirfd ->
            Files.move(original, directory.resolve("renamed"))
            SocketHelperProcess.start().use { helper ->
                val result = helper.execute(SocketOperation.OpenAt2("target", 524288, 0, 8), dirfd)
                val opened = assertInstanceOf(SocketHelperResult.Opened::class.java, result)
                opened.descriptor.use {
                    assertEquals("capability contents", Files.readString(Path.of("/proc/self/fd/${it.handle.value}")))
                    val flags = Files
                        .readAllLines(Path.of("/proc/self/fdinfo/${it.handle.value}"))
                        .first { line -> line.startsWith("flags:") }
                        .substringAfter(':')
                        .trim()
                        .toLong(8)
                    assertEquals(524288L, flags and 524288L)
                }
                assertFalse(opened.descriptor.handle.isLiveForIo())
                assertEquals(SocketHelperResult.Failed(2), helper.execute(SocketOperation.OpenAt2("", 0, 0, 0), dirfd))
                assertEquals(SocketHelperResult.Failed(18), helper.execute(SocketOperation.OpenAt2("../outside", 0, 0, 8), dirfd))
            }
        }
    }

    @Test
    fun `blocked FIFO open cancels queued work reaps child and replenishes before session teardown`() {
        val fifo = directory.resolve("fifo")
        acquireForHandoff(
            acquire = { ProcessBuilder("mkfifo", fifo.toString()).start() },
            verify = { mkfifo ->
                assertTrue(mkfifo.waitFor(5, TimeUnit.SECONDS))
                assertEquals(0, mkfifo.exitValue())
            },
            rollback = processRollback(),
        )
        Files.writeString(directory.resolve("ready"), "ready")
        val started = CountDownLatch(1)
        val pid = AtomicLong()
        val ownedHelper = java.util.concurrent.atomic
            .AtomicReference<ProcessHandle>()
        SocketHelperPool(1, 1) {
            acquireForHandoff(
                acquire = { SocketHelperProcess.start() },
                verify = { helper ->
                    val handle = helper.processHandle
                    ownedHelper.set(handle)
                    pid.set(handle.pid())
                    started.countDown()
                },
                rollback = listOf({ helper -> helper.close() }),
            )
        }.use { pool ->
            val active = pool.session()
            val source = directoryFd(directory)
            val pending = active.submit(SocketOperation.OpenAt2("fifo", 0, 0, 0), source)
            assertTrue(started.await(10, TimeUnit.SECONDS))
            awaitBlockedFifo(pid.get())
            assertFalse(pending.isDone)
            val queued = pool.session()
            val queuedFd = directoryFd(directory)
            val cancelled = queued.submit(SocketOperation.OpenAt2("must-not-create", 65, 384, 0), queuedFd)
            queued.cancel()
            assertTrue(cancelled.isCancelled)
            assertFalse(queuedFd.handle.isLiveForIo())
            active.cancel()
            active.close()
            assertTrue(pending.isCancelled)
            assertFalse(source.handle.isLiveForIo())
            assertFalse(requireNotNull(ownedHelper.get()).isAlive)
            pool.session().use { next ->
                val result = next.submit(SocketOperation.OpenAt2("ready", 0, 0, 0), directoryFd(directory)).get(10, TimeUnit.SECONDS)
                assertInstanceOf(SocketHelperResult.Opened::class.java, result).dispose()
            }
            assertFalse(Files.exists(directory.resolve("must-not-create")))
            val teardown = pool.session()
            val ending = teardown.submit(SocketOperation.OpenAt2("fifo", 0, 0, 0), directoryFd(directory))
            awaitBlockedFifo(pid.get())
            pool.close()
            assertTrue(ending.isCancelled)
        }
    }

    // Own the returned resource before discovery or registration can fail.
    @Suppress("TooGenericExceptionCaught") // Merge acquisition and every independent rollback before rethrowing.
    private fun <T> acquireForHandoff(
        acquire: () -> T,
        verify: (T) -> Unit,
        rollback: List<(T) -> Unit>,
    ): T {
        val owned = acquire()
        val primary = runCatching { verify(owned) }.exceptionOrNull()
        if (primary != null) {
            var outcome: Throwable = primary
            var interrupted = Thread.interrupted() || primary is InterruptedException
            try {
                rollback.forEach { release ->
                    try {
                        runCatching { release(owned) }.exceptionOrNull()?.let { failure ->
                            if (failure is InterruptedException) interrupted = true
                            outcome = CleanupFailures.merge(outcome, failure)
                        }
                    } finally {
                        // A nested reaper may restore its flag; defer it until all owners drain.
                        if (Thread.interrupted()) interrupted = true
                    }
                }
                throw outcome
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
        return owned
    }

    private fun processRollback(): List<(Process) -> Unit> =
        listOf(
        { process ->
            process.destroyForcibly()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var interrupted = Thread.interrupted()
            try {
                while (process.isAlive) {
                    val remaining = deadline - System.nanoTime()
                    assertTrue(remaining > 0, "Owned acquisition child must physically end")
                    try {
                        assertTrue(process.waitFor(remaining, TimeUnit.NANOSECONDS), "Owned acquisition child must physically end")
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        },
        { process -> process.outputStream.close() },
        { process -> process.inputStream.close() },
        { process -> process.errorStream.close() },
    )

    private fun retireControlProcess(process: Process) {
        val interrupted = Thread.interrupted()
        try {
            processRollback().forEach { it(process) }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    @Test
    fun `quality F10 held process timeout rolls back before acquisition escapes`() {
        val child = ProcessBuilder("sleep", "30").start()
        val timeout = IllegalStateException("modeled acquisition timeout")
        var escapedAlive = false
        try {
            val observed = assertThrows(IllegalStateException::class.java) {
                acquireForHandoff(
                    acquire = { child },
                    verify = {
                        assertFalse(it.waitFor(100, TimeUnit.MILLISECONDS), "Control child must be held live")
                        throw timeout
                    },
                    rollback = processRollback(),
                )
            }
            assertSame(timeout, observed)
            escapedAlive = child.isAlive
        } finally {
            retireControlProcess(child)
        }
        assertFalse(escapedAlive, "Acquisition failure escaped its owned live child")
    }

    @Test
    fun `quality F10 held process interruption retires before restoring owner flag`() {
        val child = ProcessBuilder("sleep", "30").start()
        var escapedAlive = false
        var restored = false
        val interruptedDuringCleanup = java.util.concurrent.atomic
            .AtomicBoolean()
        try {
            assertThrows(InterruptedException::class.java) {
                acquireForHandoff(
                    acquire = { child },
                    verify = {
                        Thread.currentThread().interrupt()
                        it.waitFor(5, TimeUnit.SECONDS)
                    },
                    rollback = listOf<(Process) -> Unit>(
                        { interruptedDuringCleanup.set(Thread.currentThread().isInterrupted) },
                    ) +
                        processRollback(),
                )
            }
            escapedAlive = child.isAlive
            restored = Thread.currentThread().isInterrupted
        } finally {
            retireControlProcess(child)
            Thread.interrupted()
        }
        assertFalse(escapedAlive, "Interrupted acquisition must physically retire child")
        assertFalse(interruptedDuringCleanup.get(), "Interrupt restoration must follow rollback")
        assertTrue(restored, "Owner interruption must be restored after rollback")
    }

    @Test
    fun `quality F10 helper identity discovery failure retires actual acquired helper`() {
        val helper = SocketHelperProcess.start()
        val ownedHandle = helper.processHandle
        val discovery = IllegalArgumentException("modeled identity discovery failure")
        var escapedAlive = false
        try {
            val observed = assertThrows(IllegalArgumentException::class.java) {
                acquireForHandoff<SocketHelperProcess>(
                    acquire = { helper },
                    verify = {
                        assertEquals(ownedHandle, it.processHandle)
                        throw discovery
                    },
                    rollback = listOf<(SocketHelperProcess) -> Unit>({ it.close() }),
                )
            }
            assertSame(discovery, observed)
            escapedAlive = ownedHandle.isAlive
        } finally {
            helper.close()
        }
        assertFalse(ownedHandle.isAlive, "Fixture helper must physically end")
        assertFalse(escapedAlive, "Factory discovery failure escaped its acquired helper")
    }

    @Test
    fun `quality F10 ordinary primary yields to late fatal after all independent rollback attempts`() {
        val child = ProcessBuilder("sleep", "30").start()
        val primary = IllegalArgumentException("modeled acquisition failure")
        val fatal = LinkageError("modeled late rollback fatal")
        val attempts = java.util.concurrent.atomic
            .AtomicInteger()
        var observed: Throwable? = null
        var escapedAlive = false
        try {
            observed = kotlin
                .runCatching {
                acquireForHandoff(
                    acquire = { child },
                    verify = { throw primary },
                    rollback = listOf<(Process) -> Unit>(
                        {
                            attempts.incrementAndGet()
                            it.outputStream.close()
                            throw fatal
                        },
                        {
                            attempts.incrementAndGet()
                            it.destroyForcibly()
                            assertTrue(it.waitFor(5, TimeUnit.SECONDS))
                        },
                        {
                            attempts.incrementAndGet()
                            it.inputStream.close()
                        },
                        {
                            attempts.incrementAndGet()
                            it.errorStream.close()
                        },
                    ),
                )
            }.exceptionOrNull()
            escapedAlive = child.isAlive
        } finally {
            retireControlProcess(child)
        }
        assertSame(fatal, observed, "Late fatal must outrank ordinary acquisition failure")
        assertEquals(4, attempts.get(), "Every independent rollback must be attempted despite fatal failure")
        assertFalse(escapedAlive, "Late fatal must not escape a live owned child")
    }

    @Test
    fun `quality F10 independent rollback continues after a stream cleanup failure`() {
        val child = ProcessBuilder("sleep", "30").start()
        val primary = IllegalArgumentException("modeled acquisition failure")
        val attempts = java.util.concurrent.atomic
            .AtomicInteger()
        var escapedAlive = false
        try {
            kotlin.runCatching {
                acquireForHandoff(
                    acquire = { child },
                    verify = { throw primary },
                    rollback = listOf<(Process) -> Unit>(
                        {
                            attempts.incrementAndGet()
                            it.outputStream.close()
                            throw java.io.IOException("modeled stream cleanup failure")
                        },
                        {
                            attempts.incrementAndGet()
                            it.destroyForcibly()
                            assertTrue(it.waitFor(5, TimeUnit.SECONDS))
                        },
                        {
                            attempts.incrementAndGet()
                            it.inputStream.close()
                        },
                        {
                            attempts.incrementAndGet()
                            it.errorStream.close()
                        },
                    ),
                )
            }
            escapedAlive = child.isAlive
        } finally {
            retireControlProcess(child)
        }
        assertEquals(4, attempts.get(), "Independent cleanup must continue after a stream cleanup failure")
        assertFalse(escapedAlive, "All-attempt rollback must physically retire child")
    }

    private fun directoryFd(path: Path): SupervisorOwnedFd =
        NativeArena.ofConfined().use { arena ->
        val opened = SupervisorDescriptorResult.from(
            LinuxNative.fileSystem.open(arena.allocateFrom(path.toString()), OpenFlags(NativeConstants.O_PATH or NativeConstants.O_CLOEXEC)),
            LinuxNative,
        )
        assertInstanceOf(SupervisorDescriptorResult.Opened::class.java, opened).descriptor
    }

    private fun awaitBlockedFifo(pid: Long) {
        awaitCondition(description = "helper enters blocking FIFO open") {
            Files.list(Path.of("/proc/$pid/task")).use { tasks ->
                tasks.anyMatch { task ->
                    try {
                        Files.readString(task.resolve("wchan")).trim() == "wait_for_partner"
                    } catch (_: java.nio.file.NoSuchFileException) {
                        false // A JVM housekeeping thread can exit during enumeration.
                    }
                }
            }
        }
    }
}
