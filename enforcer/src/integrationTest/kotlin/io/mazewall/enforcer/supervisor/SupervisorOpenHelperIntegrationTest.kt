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
    @TempDir lateinit var directory: Path

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
        val mkfifo = ProcessBuilder("mkfifo", fifo.toString()).start()
        assertTrue(mkfifo.waitFor(5, TimeUnit.SECONDS))
        assertEquals(0, mkfifo.exitValue())
        Files.writeString(directory.resolve("ready"), "ready")
        val started = CountDownLatch(1)
        val pid = AtomicLong()
        SocketHelperPool(1, 1) {
            val before = ProcessHandle
                .current()
                .children()
                .map { it.pid() }
                .toList()
                .toSet()
            SocketHelperProcess.start().also {
                pid.set(
                    ProcessHandle
                    .current()
                    .children()
                    .filter { it.pid() !in before }
                    .findFirst()
                    .orElseThrow()
                    .pid(),
                )
                started.countDown()
            }
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
            assertFalse(ProcessHandle.of(pid.get()).map { it.isAlive }.orElse(false))
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
