package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.api.MockNativeFileSystem
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.networking.ConfinedDeadline
import io.mazewall.ffi.networking.ConfinedHelperChannel
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.NativeFileSystem
import io.mazewall.platform.native.getFdOrThrow
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LiveInstallationLifetimeTest {
    private val real = LinuxNative.snapshot()
    private val type = Class.forName("io.mazewall.enforcer.internal.supervisor.LiveInstallationLifetime")

    private fun owner(
        native: NativeEngine,
        thread: Thread = Thread.currentThread(),
    ): Any =
        type
            .getDeclaredConstructor(Thread::class.java, NativeEngine::class.java)
            .apply { isAccessible = true }
            .newInstance(thread, native)

    private fun call(
        owner: Any,
        name: String,
        vararg args: Any,
    ): Any? {
        val method = type.declaredMethods.single { it.name == name && it.parameterCount == args.size }
        method.isAccessible = true
        return try {
            method.invoke(owner, *args)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }

    private fun socket(): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> =
        real.networking.socket(1, 1 or 0x80000, 0).getFdOrThrow("create Live lifetime test socket", FileDescriptorRole.UnixSocket)

    private fun engine(beforeClose: (FileDescriptor<*, FdState.Open, FdOwnership.Owned>) -> Unit): MockNativeEngine =
        MockNativeEngine(
            fileSystem = object : MockNativeFileSystem() {
            override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                beforeClose(fd)
                return real.fileSystem.close(fd)
            }
            },
        ).apply { pollResult = LinuxNative.SyscallResult.Success(1L) }

    private fun pair(): Pair<
        FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    > =
        NativeArena.ofConfined().use { arena ->
        val result = real.networking.socketpair(1, 1 or 0x80000, 0, arena.allocate(8))
        result.getFdOrThrow("Live socket owner", FileDescriptorRole.UnixSocket) to
            result.getFdOrThrow("Live socket peer", FileDescriptorRole.UnixSocket)
    }

    private fun send(
        peer: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        bytes: ByteArray,
    ) {
        val channel = ConfinedHelperChannel(real, peer, { false }, { true })
        channel.send(bytes, ConfinedDeadline.mint(real) { TimeUnit.SECONDS.toNanos(5) })
    }

    private fun observingEngine(closed: CountDownLatch): NativeEngine =
        object : NativeEngine by real {
        override val fileSystem = object : NativeFileSystem by real.fileSystem {
            override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                val result = real.fileSystem.close(fd)
                closed.countDown()
                return result
            }
        }
    }

    private fun closeIfLive(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>) {
        if (fd.isLiveForIo()) {
            real.fileSystem
                .close(fd)
                .getOrThrow("close Live test socket")
        }
    }

    @Test
    fun `owner thread exit stops and retires its acknowledged socket`() {
        val (socket, peer) = pair()
        val ownerRelease = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val ownerThread = Thread { ownerRelease.await() }.apply { start() }
        val lifetime = owner(observingEngine(closed), ownerThread)
        val ready = CountDownLatch(1)
        var attached = false
        try {
            call(lifetime, "start")
            call(lifetime, "requireReusable")
            attached = true
            call(lifetime, "attach", socket, ready)
            send(peer, byteArrayOf(0xac.toByte()))
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            call(lifetime, "requireReusable")
            ownerRelease.countDown()
            ownerThread.join(5000)
            assertFalse(ownerThread.isAlive)
            assertTrue(closed.await(5, TimeUnit.SECONDS), "watcher must cause physical socket close")
            call(lifetime, "closeAndAwait")
            assertIs<IllegalStateException>(runCatching { call(lifetime, "requireReusable") }.exceptionOrNull())
            assertFalse(socket.isLiveForIo())
        } finally {
            ownerRelease.countDown()
            ownerThread.join(5000)
            closeIfLive(peer)
            if (attached) runCatching { call(lifetime, "closeAndAwait") } else closeIfLive(socket)
        }
    }

    @Test
    fun `remote EOF after ACK poisons the original listener lifetime`() {
        val (socket, peer) = pair()
        val closed = CountDownLatch(1)
        val lifetime = owner(observingEngine(closed))
        val ready = CountDownLatch(1)
        var attached = false
        try {
            call(lifetime, "start")
            attached = true
            call(lifetime, "attach", socket, ready)
            send(peer, byteArrayOf(0xac.toByte()))
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            call(lifetime, "requireReusable")
            closeIfLive(peer)
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            val problem = runCatching { call(lifetime, "closeAndAwait") }.exceptionOrNull()
            assertIs<java.io.IOException>(problem)
            assertIs<IllegalStateException>(runCatching { call(lifetime, "requireReusable") }.exceptionOrNull())
            assertFalse(socket.isLiveForIo())
        } finally {
            closeIfLive(peer)
            if (attached) runCatching { call(lifetime, "closeAndAwait") } else closeIfLive(socket)
        }
    }

    @Test
    fun `invalid ACK publishes startup failure only after socket cleanup`() {
        val (socket, peer) = pair()
        val closed = CountDownLatch(1)
        val lifetime = owner(observingEngine(closed))
        val ready = CountDownLatch(1)
        var attached = false
        try {
            call(lifetime, "start")
            attached = true
            call(lifetime, "attach", socket, ready)
            send(peer, byteArrayOf(0))
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            assertEquals(0L, closed.count, "failed startup must close socket before readiness is released")
            assertIs<IllegalStateException>(runCatching { call(lifetime, "requireReusable") }.exceptionOrNull())
            val problem = runCatching { call(lifetime, "closeAndAwait") }.exceptionOrNull()
            assertIs<IllegalStateException>(problem)
            assertTrue(problem.message.orEmpty().contains("acknowledgement"))
            assertFalse(socket.isLiveForIo())
        } finally {
            closeIfLive(peer)
            if (attached) runCatching { call(lifetime, "closeAndAwait") } else closeIfLive(socket)
        }
    }

    @Test
    fun `malformed frame after ACK poisons the listener without false reuse`() {
        val (socket, peer) = pair()
        val closed = CountDownLatch(1)
        val lifetime = owner(observingEngine(closed))
        val ready = CountDownLatch(1)
        var attached = false
        try {
            call(lifetime, "start")
            attached = true
            call(lifetime, "attach", socket, ready)
            send(peer, byteArrayOf(0xac.toByte()))
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            call(lifetime, "requireReusable")
            send(peer, ByteArray(64))
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertNotNull(runCatching { call(lifetime, "closeAndAwait") }.exceptionOrNull())
            assertIs<IllegalStateException>(runCatching { call(lifetime, "requireReusable") }.exceptionOrNull())
            assertFalse(socket.isLiveForIo())
        } finally {
            closeIfLive(peer)
            if (attached) runCatching { call(lifetime, "closeAndAwait") } else closeIfLive(socket)
        }
    }

    @Test
    fun `close waits until socket owner physically exits`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val native = engine {
            entered.countDown()
            release.await()
        }
        val owner = owner(native)
        val ready = CountDownLatch(1)
        call(owner, "attach", socket(), ready)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val closer = Thread {
            try {
                call(owner, "closeAndAwait")
            } catch (_: Throwable) {
                // EOF during startup is retained after physical cleanup.
            } finally {
                done.countDown()
            }
        }
        closer.start()
        try {
            assertFalse(done.await(100, TimeUnit.MILLISECONDS))
            assertEquals(1L, ready.count)
        } finally {
            release.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            closer.join()
        }
    }

    @Test
    fun `timeout reports failure while reactor still owns socket`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val native = engine {
            entered.countDown()
            try {
                release.await()
            } finally {
                closed.countDown()
            }
        }
        val owner = owner(native)
        val ready = CountDownLatch(1)
        call(owner, "attach", socket(), ready)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            val failure = runCatching { call(owner, "closeAndAwait") }.exceptionOrNull()
            assertIs<IllegalStateException>(failure)
            assertEquals(1L, closed.count)
            assertEquals(1L, ready.count)
        } finally {
            release.countDown()
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertTrue(ready.await(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `concurrent stop retains actual socket failure`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val expected = java.io.IOException("poll failed during stop")
        val native = engine { }.apply {
            onPoll = { _, _, _ ->
                entered.countDown()
                release.await()
                throw expected
            }
        }
        val owner = owner(native)
        val ready = CountDownLatch(1)
        call(owner, "attach", socket(), ready)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            call(owner, "close")
        } finally {
            release.countDown()
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        assertSame(expected, runCatching { call(owner, "closeAndAwait") }.exceptionOrNull())
    }

    @Test
    fun `fatal close failure retains startup failure and is reported after close`() {
        val fatal = AssertionError("close failure")
        val closeCount = java.util.concurrent.atomic
            .AtomicInteger()
        val native = object : MockNativeEngine(
            fileSystem = object : MockNativeFileSystem() {
            override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                closeCount.incrementAndGet()
                real.fileSystem
                    .close(fd)
                    .getOrThrow("close test socket")
                throw fatal
            }
            },
        ) {}.apply { pollResult = LinuxNative.SyscallResult.Success(1L) }
        val owner = owner(native)
        val ready = CountDownLatch(1)
        call(owner, "attach", socket(), ready)
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        val failure = runCatching { call(owner, "closeAndAwait") }.exceptionOrNull()
        assertSame(fatal, failure)
        assertTrue(fatal.suppressed.isNotEmpty())
        assertEquals(1, closeCount.get())
    }
}
