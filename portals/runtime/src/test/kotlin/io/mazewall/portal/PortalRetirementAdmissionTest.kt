package io.mazewall.portal

import io.mazewall.enforcer.api.*
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.TestFileDescriptors
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.NativeFileSystem
import io.mazewall.platform.native.RawSyscallOperations
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PortalRetirementAdmissionTest {
    @Test
    fun `socket retirement retries a pre-native failure while admission stays closed`() {
        var attempts = 0
        val failure = IOException("before socket close")
        val sockets = object : SocketManager by RealSocketManager {
            override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>) {
                if (++attempts == 1) throw failure
                RealSocketManager.close(fd)
            }
        }
        withPair(sockets) { channel, socket, _ ->
            assertSame(failure, assertThrows(IOException::class.java) { channel.close() })
            assertTrue(isLive(socket.value))
            assertThrows(IllegalStateException::class.java) { channel.receive(10) }
            channel.close()
            channel.close()
            assertEquals(2, attempts)
            assertFalse(isLive(socket.value))
        }
    }

    @Test
    fun `terminal native socket close result is never dispatched a second time`() {
        withPair { channel, socket, _ ->
            val runtime = LinuxNative.snapshot()
            var attempts = 0
            LinuxNative.setEngine(object : NativeEngine by runtime {
                override val fileSystem = object : NativeFileSystem by runtime.fileSystem {
                    override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                        if (fd.value != socket.value) return runtime.fileSystem.close(fd)
                        attempts++
                        runtime.fileSystem.close(fd).getOrThrow("fixture kernel close")
                        return LinuxNative.SyscallResult.Error(NativeConstants.EBADF, -1)
                    }
                }
            })
            try {
                assertThrows(IllegalStateException::class.java) { channel.close() }
                channel.close()
                assertEquals(1, attempts)
            } finally {
                LinuxNative.resetToDefault()
            }
        }
    }

    @Test
    fun `failed partial owners survive terminal receipt and drain on later close`(
        @TempDir dir: Path,
    ) {
        Files.writeString(dir.resolve("grant"), "x")
        openGrantedRead(dir, "grant").use { source ->
            withPair { channel, socket, peer ->
                val sourceNumber = source.borrow { it.value }
                val runtime = LinuxNative.snapshot()
                val ordinary = IOException("first partial owner close failed")
                val fatal = AssertionError("second partial owner close failed")
                val received = linkedMapOf<Int, FileDescriptor<*, FdState.Open, FdOwnership.Owned>>()
                val attempts = mutableListOf<Int>()
                var failCloses = true
                LinuxNative.setEngine(object : NativeEngine by runtime {
                    override val fileSystem = object : NativeFileSystem by runtime.fileSystem {
                        override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                            if (fd.value !in setOf(socket.value, peer.value, sourceNumber)) {
                                received.putIfAbsent(fd.value, fd)
                                attempts.add(fd.value)
                                if (failCloses) {
                                    when (received.keys.indexOf(fd.value)) {
                                    0 -> throw ordinary
                                    1 -> throw fatal
                                }
                                }
                            }
                            return runtime.fileSystem.close(fd)
                        }
                    }
                })
                try {
                    write(peer, PortalFrame(PortalKind.Request, 1, PortalMethod.Checksum, PortalPayload(ByteArray(0)), 4).headerBytes())
                    repeat(3) { source.borrow { assertTrue(RealSocketManager.sendDescriptor(peer, it)) } }
                    assertSame(fatal, assertThrows(AssertionError::class.java) { channel.receive(500) })
                    assertEquals(3, received.size, "all independent close actions must be attempted")
                    assertTrue(fatal.suppressed.contains(ordinary))
                    assertTrue(fatal.suppressed.any { it is PortalIncompleteFrameException })
                    val ids = received.keys.toList()
                    assertTrue(isLive(ids[0]))
                    assertTrue(isLive(ids[1]))
                    assertFalse(isLive(ids[2]))
                    assertFalse(isLive(socket.value), "fatal grant cleanup must not skip socket retirement")
                    failCloses = false
                    channel.close()
                    channel.close()
                    assertEquals(listOf(ids[0], ids[1], ids[2], ids[0], ids[1]), attempts)
                    ids.forEach { assertFalse(isLive(it)) }
                } finally {
                    failCloses = false
                    LinuxNative.resetToDefault()
                    channel.close()
                    received.values.filter { isLive(it.value) }.forEach { RealSocketManager.close(it) }
                }
            }
        }
    }

    @Test
    fun `contended channel admission consumes the short callers deadline`() {
        withPair { channel, socket, _ ->
            val enteredPoll = CountDownLatch(1)
            val runtime = LinuxNative.snapshot()
            LinuxNative.setEngine(object : NativeEngine by runtime {
                override val raw = object : RawSyscallOperations by runtime.raw {
                    override fun poll(
                        fds: ManagedSegment,
                        nfds: Long,
                        timeout: Int,
                    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                        if (fds.readInt(0) == socket.value) enteredPoll.countDown()
                        return runtime.raw.poll(fds, nfds, timeout)
                    }
                }
            })
            val executor = Executors.newFixedThreadPool(2)
            try {
                val active = executor.submit { assertThrows(IOException::class.java) { channel.receive(30_000) } }
                assertTrue(enteredPoll.await(2, TimeUnit.SECONDS))
                val contended = executor.submit { assertThrows(IOException::class.java) { channel.receive(Deadline.afterMillis(75)) } }
                contended.get(1, TimeUnit.SECONDS)
                assertFalse(active.isDone, "failed admission must not terminate another caller's active frame")
                channel.close()
                active.get(1, TimeUnit.SECONDS)
            } finally {
                channel.close()
                executor.shutdownNow()
                executor.awaitTermination(2, TimeUnit.SECONDS)
                LinuxNative.resetToDefault()
            }
        }
    }

    @Test
    fun `channel cancellation interrupts capability admission without waiting for caller borrow`(
        @TempDir dir: Path,
    ) {
        Files.writeString(dir.resolve("grant"), "x")
        openGrantedRead(dir, "grant").use { capability ->
            withPair { channel, _, peer ->
                val enteredBorrow = CountDownLatch(1)
                val releaseBorrow = CountDownLatch(1)
                val executor = Executors.newFixedThreadPool(3)
                try {
                    val borrower = executor.submit {
                        capability.borrow {
                        enteredBorrow.countDown()
                        assertTrue(releaseBorrow.await(5, TimeUnit.SECONDS))
                    }
                    }
                    assertTrue(enteredBorrow.await(2, TimeUnit.SECONDS))
                    val sender = executor.submit {
                        assertThrows(IOException::class.java) {
                            channel.sendCapabilities(PortalFrame(PortalKind.Request, 1, PortalMethod.Checksum, PortalPayload(ByteArray(0)), 1), listOf(capability), Deadline.afterMillis(30_000))
                        }
                    }
                    readHeader(peer) // Proves sender entered the channel and reached the held capability.
                    val closed = executor.submit { channel.close() }
                    closed.get(1, TimeUnit.SECONDS)
                    sender.get(1, TimeUnit.SECONDS)
                    assertFalse(borrower.isDone)
                    releaseBorrow.countDown()
                    borrower.get(1, TimeUnit.SECONDS)
                } finally {
                    releaseBorrow.countDown()
                    channel.close()
                    executor.shutdownNow()
                    executor.awaitTermination(2, TimeUnit.SECONDS)
                }
            }
        }
    }

    private fun withPair(
        sockets: SocketManager = RealSocketManager,
        action: (PortalChannel, FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>, FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>) -> Unit,
    ) {
        NativeArena.ofConfined().use { arena ->
            val pair = arena.allocate(8)
            LinuxNative.networking.socketpair(1, 1 or NativeConstants.SOCK_CLOEXEC, 0, pair).getOrThrow("socketpair")
            val socket = TestFileDescriptors
                .adopt(pair.readInt(0), FileDescriptorRole.UnixSocket)
            val peer = TestFileDescriptors
                .adopt(pair.readInt(4), FileDescriptorRole.UnixSocket)
            val channel = PortalChannel(socket, sockets)
            try {
                action(channel, socket, peer)
            } finally {
                channel.close()
                if (isLive(socket.value)) RealSocketManager.close(socket)
                RealSocketManager.close(peer)
            }
        }
    }

    private fun isLive(fd: Int): Boolean = Files.exists(Path.of("/proc/self/fd", fd.toString()), java.nio.file.LinkOption.NOFOLLOW_LINKS)

    private fun write(
        peer: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        bytes: ByteArray,
    ) {
        NativeArena.ofConfined().use { arena ->
            val buffer = arena.allocate(bytes.size.toLong())
            bytes.forEachIndexed { i, b -> buffer.writeByte(i.toLong(), b) }
            SocketIo.writeFully(LinuxNative.memory, peer, buffer, bytes.size.toLong()).getOrThrow("fixture write")
        }
    }

    private fun readHeader(peer: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>) {
        NativeArena.ofConfined().use { arena ->
            val pollfd = PollFdSegment.of(arena.allocate(Layouts.POLLFD))
            pollfd.setFd(peer.value)
            pollfd.setEvents(NativeConstants.POLLIN)
            val size = PortalFrame.HEADER_SIZE.toLong()
            SocketIo
                .readFully(
                LinuxNative.memory,
                peer,
                arena.allocate(size),
                size,
                Deadline.afterMillis(2_000),
                SocketPoll { LinuxNative.raw.poll(pollfd.managed, 1, it) },
            ).getOrThrow("fixture header read")
        }
    }
}
