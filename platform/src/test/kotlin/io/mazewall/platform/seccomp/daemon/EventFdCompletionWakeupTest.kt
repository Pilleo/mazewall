package io.mazewall.platform.seccomp.daemon

import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.platform.native.LinuxNative
import kotlin.test.Test
import kotlin.test.assertEquals

class EventFdCompletionWakeupTest {
    @Test
    fun `signal makes the owned eventfd readable until the reactor drains it`() {
        EventFdCompletionWakeup.create(LinuxNative).use { wakeup ->
            NativeArena.ofConfined().use { arena ->
                val pollFd = PollFdSegment.of(arena.allocate(Layouts.POLLFD))
                pollFd.setFd(wakeup.pollFd)
                pollFd.setEvents(NativeConstants.POLLIN)

                wakeup.signal()
                assertEquals(1L, LinuxNative.raw.poll(pollFd.managed, 1L, 0).getOrThrow("poll signalled eventfd"))
                assertEquals(NativeConstants.POLLIN, pollFd.getRevents())

                wakeup.drain()
                pollFd.setRevents(0)
                assertEquals(0L, LinuxNative.raw.poll(pollFd.managed, 1L, 0).getOrThrow("poll drained eventfd"))
            }
        }
    }

    @Test
    fun `failed dispatched close retains failure and never closes reused descriptor`() {
        val original = LinuxNative.snapshot()
        val failure = IllegalStateException("reported after real eventfd close")
        var closes = 0
        val engine = object : io.mazewall.platform.native.NativeEngine by original {
            override val fileSystem = object : io.mazewall.platform.native.NativeFileSystem by original.fileSystem {
                override fun close(
                    fd: io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership.Owned>,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    closes++
                    original.fileSystem.close(fd).getOrThrow("real eventfd close")
                    throw failure
                }
            }
        }
        val wakeup = EventFdCompletionWakeup.create(engine)
        val number = wakeup.pollFd
        val replacements = mutableListOf<EventFdCompletionWakeup>()
        try {
            kotlin.test.assertSame(failure, kotlin.test.assertFailsWith<IllegalStateException> { wakeup.close() })
            repeat(64) { if (replacements.none { it.pollFd == number }) replacements.add(EventFdCompletionWakeup.create(original)) }
            val replacement = checkNotNull(replacements.firstOrNull { it.pollFd == number })
            kotlin.test.assertSame(failure, kotlin.test.assertFailsWith<IllegalStateException> { wakeup.close() })
            assertEquals(1, closes)
            replacement.signal()
            replacement.drain()
        } finally {
            replacements.forEach { it.close() }
        }
    }

    @Test
    fun `close remains pending until an admitted write physically exits`() {
        val original = LinuxNative.snapshot()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val error = java.util.concurrent.atomic
            .AtomicReference<Throwable?>()
        var closes = 0
        val engine = object : io.mazewall.platform.native.NativeEngine by original {
            override val memory = object : io.mazewall.platform.native.NativeMemory by original.memory {
                override fun write(
                    fd: io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership>,
                    buf: io.mazewall.ffi.memory.ManagedSegment,
                    count: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    return original.memory.write(fd, buf, count)
                }
            }
            override val fileSystem = object : io.mazewall.platform.native.NativeFileSystem by original.fileSystem {
                override fun close(
                    fd: io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership.Owned>,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    closes++
                    return original.fileSystem.close(fd)
                }
            }
        }
        val wakeup = EventFdCompletionWakeup.create(engine)
        val worker = Thread.ofPlatform().start {
            try {
            wakeup.signal()
        } catch (problem: Throwable) {
            error.set(problem)
        }
        }
        try {
            check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            kotlin.test.assertFailsWith<IllegalStateException> { wakeup.close() }
            assertEquals(0, closes)
        } finally {
            release.countDown()
            worker.join(5000)
        }
        check(!worker.isAlive)
        kotlin.test.assertNull(error.get())
        wakeup.close()
        assertEquals(1, closes)
    }

    @Test
    fun `close waits for admitted drain and concurrent callers cannot dispatch`() {
        val original = LinuxNative.snapshot()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val error = java.util.concurrent.atomic
            .AtomicReference<Throwable?>()
        val closes = java.util.concurrent.atomic
            .AtomicInteger()
        val engine = object : io.mazewall.platform.native.NativeEngine by original {
            override val memory = object : io.mazewall.platform.native.NativeMemory by original.memory {
                override fun read(
                    fd: io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership>,
                    buf: io.mazewall.ffi.memory.ManagedSegment,
                    count: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    return original.memory.read(fd, buf, count)
                }
            }
            override val fileSystem = object : io.mazewall.platform.native.NativeFileSystem by original.fileSystem {
                override fun close(
                    fd: io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership.Owned>,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    closes.incrementAndGet()
                    return original.fileSystem.close(fd)
                }
            }
        }
        val wakeup = EventFdCompletionWakeup.create(engine)
        wakeup.signal()
        val worker = Thread.ofPlatform().start {
            try {
            wakeup.drain()
        } catch (problem: Throwable) {
            error.set(problem)
        }
        }
        try {
            check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val outcomes = List(2) {
                java.util.concurrent.atomic
                .AtomicReference<Throwable?>()
            }
            val callers = outcomes.map { outcome -> Thread.ofPlatform().start { outcome.set(runCatching { wakeup.close() }.exceptionOrNull()) } }
            callers.forEach {
                it.join(5000)
                check(!it.isAlive)
            }
            check(outcomes.all { it.get() is IllegalStateException })
            assertEquals(0, closes.get())
        } finally {
            release.countDown()
            worker.join(5000)
        }
        check(!worker.isAlive)
        kotlin.test.assertNull(error.get())
        wakeup.close()
        wakeup.close()
        assertEquals(1, closes.get())
    }

    @Test
    fun `fatal dispatched close remains the exact failure without replay`() {
        val original = LinuxNative.snapshot()
        val fatal = AssertionError("fatal after real close")
        var closes = 0
        val engine = object : io.mazewall.platform.native.NativeEngine by original {
            override val fileSystem = object : io.mazewall.platform.native.NativeFileSystem by original.fileSystem {
                override fun close(
                    fd: io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership.Owned>,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    closes++
                    original.fileSystem.close(fd).getOrThrow("real eventfd close")
                    throw fatal
                }
            }
        }
        val wakeup = EventFdCompletionWakeup.create(engine)
        kotlin.test.assertSame(fatal, kotlin.test.assertFailsWith<AssertionError> { wakeup.close() })
        kotlin.test.assertSame(fatal, kotlin.test.assertFailsWith<AssertionError> { wakeup.close() })
        assertEquals(1, closes)
    }

    @Test
    fun `retirement exposes the exact dispatched failure without retry`() {
        val original = LinuxNative.snapshot()
        val failure = IllegalStateException("synthetic failure after real close")
        var attempts = 0
        val engine = object : io.mazewall.platform.native.NativeEngine by original {
            override val fileSystem = object : io.mazewall.platform.native.NativeFileSystem by original.fileSystem {
                override fun close(
                    fd: io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership.Owned>,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    attempts++
                    original.fileSystem.close(fd).getOrThrow("real eventfd close")
                    throw failure
                }
            }
        }
        val wakeup = EventFdCompletionWakeup.create(engine)
        val first = kotlin.test.assertIs<SessionRetirement.Unknown>(wakeup.retire())
        kotlin.test.assertSame(failure, first.failure)
        val second = kotlin.test.assertIs<SessionRetirement.Unknown>(wakeup.retire())
        kotlin.test.assertSame(failure, second.failure)
        assertEquals(1, attempts)
    }

    @Test
    fun `retirement reports pending until admitted write exits`() {
        val original = LinuxNative.snapshot()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val problem = java.util.concurrent.atomic
            .AtomicReference<Throwable?>()
        val engine = object : io.mazewall.platform.native.NativeEngine by original {
            override val memory = object : io.mazewall.platform.native.NativeMemory by original.memory {
                override fun write(
                    fd: io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership>,
                    buf: io.mazewall.ffi.memory.ManagedSegment,
                    count: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    return original.memory.write(fd, buf, count)
                }
            }
        }
        val wakeup = EventFdCompletionWakeup.create(engine)
        val worker = Thread.ofPlatform().start {
            try {
            wakeup.signal()
        } catch (failure: Throwable) {
            problem.set(failure)
        }
        }
        try {
            check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            kotlin.test.assertSame(SessionRetirement.Pending, wakeup.retire())
        } finally {
            release.countDown()
            worker.join(5000)
        }
        check(!worker.isAlive)
        kotlin.test.assertNull(problem.get())
        kotlin.test.assertSame(SessionRetirement.Complete, wakeup.retire())
    }

    @Test
    fun `native close error remains unknown and is never retried`() {
        val original = LinuxNative.snapshot()
        var attempts = 0
        val engine = object : io.mazewall.platform.native.NativeEngine by original {
            override val fileSystem = object : io.mazewall.platform.native.NativeFileSystem by original.fileSystem {
                override fun close(
                    fd: io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership.Owned>,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    attempts++
                    original.fileSystem.close(fd).getOrThrow("actual eventfd close")
                    return LinuxNative.SyscallResult.Error(NativeConstants.EIO, -1L)
                }
            }
        }
        val wakeup = EventFdCompletionWakeup.create(engine)
        val first = kotlin.test.assertIs<SessionRetirement.Unknown>(wakeup.retire())
        val second = kotlin.test.assertIs<SessionRetirement.Unknown>(wakeup.retire())
        kotlin.test.assertSame(first.failure, second.failure)
        assertEquals(1, attempts)
    }
}
