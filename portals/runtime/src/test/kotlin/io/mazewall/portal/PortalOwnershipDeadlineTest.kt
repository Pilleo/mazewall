package io.mazewall.portal

import io.mazewall.enforcer.api.*
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.readByte
import io.mazewall.ffi.memory.writeByte
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PortalOwnershipDeadlineTest {
    @Test
    fun `clean peer EOF has a distinct outcome but partial header EOF is failure`(
        @TempDir dir: Path,
    ) {
        connection(dir) { channel, peer ->
            peer.close()
            assertThrows(PortalPeerClosedException::class.java) { channel.receive(1_000) }
        }
        connection(Files.createDirectory(dir.resolve("partial"))) { channel, peer ->
            peer.write(ByteBuffer.wrap(byteArrayOf(1)))
            peer.close()
            val failure = assertThrows(java.io.IOException::class.java) { channel.receive(1_000) }
            assertFalse(failure is PortalPeerClosedException)
            assertThrows(IllegalStateException::class.java) { channel.receive(1_000) }
        }
    }

    @Test
    fun `complete transfer preserves descriptor order and independent received ownership`(
        @TempDir dir: Path,
    ) {
        Files.writeString(dir.resolve("first"), "a")
        Files.writeString(dir.resolve("second"), "b")
        val grants = listOf(openGrantedRead(dir, "first"), openGrantedRead(dir, "second"))
        val server = RealSocketManager.createUnixServer(dir.resolve("order.sock").toString())
        try {
            PortalChannel(RealSocketManager.connect(dir.resolve("order.sock").toString())).use { sender ->
                PortalChannel(RealSocketManager.accept(server)).use { receiver ->
                    val frame = PortalFrame(PortalKind.Request, 3, PortalMethod.Echo, PortalPayload(byteArrayOf(5, 6)), 2)
                    sender.sendCapabilities(frame, grants, Deadline.afterMillis(2_000))
                    val (received, owners) = receiver.receive(2_000)
                    try {
                        assertEquals(frame, received)
                        Capability.closeAll(grants)
                        val bytes = owners.map { owner ->
                            owner.borrow { fd ->
                            io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
                                val buffer = arena.allocate(1)
                                assertEquals(1L, LinuxNative.memory.read(fd, buffer, 1).getOrThrow("read received"))
                                buffer.readByte(0)
                            }
                        }
                        }
                        assertEquals(listOf('a'.code.toByte(), 'b'.code.toByte()), bytes)
                    } finally {
                        Capability.closeAll(owners)
                    }
                }
            }
        } finally {
            Capability.closeAll(grants)
            RealSocketManager.close(server)
        }
    }

    @Test
    fun `pre-dispatch close failure remains retryable without reopening admission`(
        @TempDir dir: Path,
    ) {
        Files.writeString(dir.resolve("grant"), "x")
        val capability = openGrantedRead(dir, "grant")
        val runtime = LinuxNative.snapshot()
        val failure = java.io.IOException("before native close")
        var attempts = 0
        LinuxNative.setEngine(object : io.mazewall.platform.native.NativeEngine by runtime {
            override val fileSystem = object : io.mazewall.platform.native.NativeFileSystem by runtime.fileSystem {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    if (++attempts == 1) throw failure
                    return runtime.fileSystem.close(fd)
                }
            }
        })
        try {
            assertSame(failure, assertThrows(java.io.IOException::class.java) { capability.close() })
            assertThrows(IllegalStateException::class.java) { capability.borrow { fail<Unit>("closing owner admitted") } }
            capability.close()
            capability.close()
            assertEquals(2, attempts)
        } finally {
            LinuxNative.resetToDefault()
            capability.close()
        }
    }

    @Test
    fun `structured capability retirement retains only pre-native close failures for retry`(
        @TempDir dir: Path,
    ) {
        Files.writeString(dir.resolve("grant"), "x")
        val capability = openGrantedRead(dir, "grant")
        val runtime = LinuxNative.snapshot()
        val failure = java.io.IOException("before native close")
        var attempts = 0
        LinuxNative.setEngine(object : io.mazewall.platform.native.NativeEngine by runtime {
            override val fileSystem = object : io.mazewall.platform.native.NativeFileSystem by runtime.fileSystem {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    if (++attempts == 1) throw failure
                    return runtime.fileSystem.close(fd)
                }
            }
        })
        try {
            val first = Capability.retireAll(listOf(capability))
            assertSame(failure, first.failureOrNull())
            assertEquals(listOf(capability), first.retained)

            val second = Capability.retireAll(first.retained)
            assertTrue(second.complete)
            assertTrue(second.retained.isEmpty())
            assertEquals(2, attempts)
        } finally {
            LinuxNative.resetToDefault()
            capability.close()
        }
    }

    @Test
    fun `header declaring an absent descriptor is terminal rather than an idle tick`(
        @TempDir dir: Path,
    ) {
        connection(dir) { channel, peer ->
            peer.write(ByteBuffer.wrap(PortalFrame(PortalKind.Request, 1, PortalMethod.Checksum, PortalPayload(ByteArray(0)), 1).headerBytes()))
            assertThrows(PortalIncompleteFrameException::class.java) { channel.receive(500) }
        }
    }

    @Test
    fun `close retires a polling receive without waiting its whole RPC budget`(
        @TempDir dir: Path,
    ) {
        connection(dir) { channel, _ ->
            val executor = Executors.newSingleThreadExecutor()
            val entered = CountDownLatch(1)
            try {
                val result = executor.submit {
                    entered.countDown()
                    assertThrows(Exception::class.java) { channel.receive(30_000) }
                }
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                channel.close()
                result.get(1, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `borrow cannot close its own descriptor while still using it`(
        @TempDir dir: Path,
    ) {
        Files.writeString(dir.resolve("grant"), "x")
        openGrantedRead(dir, "grant").use { capability ->
            capability.borrow {
                assertThrows(IllegalStateException::class.java) { capability.close() }
                capability.borrow { fd -> assertTrue(LinuxNative.raw.fcntl(fd, 1, 0).getOrThrow("live grant") >= 0) }
            }
        }
    }

    @Test
    fun `partial payload is terminal and never reinterpreted as another header`(
        @TempDir dir: Path,
    ) {
        connection(dir) { channel, peer ->
            val frame = PortalFrame(PortalKind.Request, 1, PortalMethod.Echo, PortalPayload(byteArrayOf(7, 8)), 0)
            peer.write(ByteBuffer.wrap(frame.headerBytes() + byteArrayOf(7)))
            assertThrows(PortalIncompleteFrameException::class.java) { channel.receive(30) }
            assertThrows(IllegalStateException::class.java) { channel.receive(30) }
        }
    }

    @Test
    fun `already consumed RPC budget is not reset despite readable complete frame`(
        @TempDir dir: Path,
    ) {
        connection(dir) { channel, peer ->
            peer.write(ByteBuffer.wrap(PortalFrame(PortalKind.Request, 1, PortalMethod.Echo, PortalPayload(byteArrayOf(7)), 0).headerBytes() + byteArrayOf(7)))
            assertThrows(java.io.IOException::class.java) { channel.receive(Deadline.afterMillis(0)) }
            assertArrayEquals(
                byteArrayOf(7),
                channel
                .receive(2_000)
                .first.payload
                .copyToByteArray(),
            )
        }
    }

    @Test
    fun `missing second descriptor closes first received owner`(
        @TempDir dir: Path,
    ) {
        Files.writeString(dir.resolve("unique-grant"), "x")
        openGrantedRead(dir, "unique-grant").use { grant ->
            val path = dir.resolve("fd.sock").toString()
            val server = RealSocketManager.createUnixServer(path)
            val sender = RealSocketManager.connect(path)
            try {
                PortalChannel(RealSocketManager.accept(server)).use { channel ->
                    val frame = PortalFrame(PortalKind.Request, 1, PortalMethod.Checksum, PortalPayload(ByteArray(0)), 2)
                    NativeArena.ofConfined().use { arena ->
                        val bytes = frame.headerBytes()
                        val buffer = arena.allocate(bytes.size.toLong())
                        bytes.forEachIndexed { i, b -> buffer.writeByte(i.toLong(), b) }
                        SocketIo.writeFully(LinuxNative.memory, sender, buffer, bytes.size.toLong()).getOrThrow("write fixture")
                    }
                    grant.borrow { assertTrue(RealSocketManager.sendDescriptor(sender, it)) }
                    assertThrows(PortalIncompleteFrameException::class.java) { channel.receive(1_000) }
                    val pathText = dir.resolve("unique-grant").toString()
                    val surviving = Files.list(Path.of("/proc/self/fd")).use { fds ->
                        fds.filter { runCatching { Files.readSymbolicLink(it).toString() }.getOrNull() == pathText }.count()
                    }
                    assertEquals(1L, surviving, "only broker's original grant may survive failed handoff")
                }
            } finally {
                RealSocketManager.close(sender)
                RealSocketManager.close(server)
            }
        }
    }

    @Test
    fun `partial header timeout is terminal but no bytes is idle`(
        @TempDir dir: Path,
    ) {
        connection(dir) { channel, peer ->
            assertThrows(PortalReadTimeoutException::class.java) { channel.receive(20) }
            peer.write(ByteBuffer.wrap(byteArrayOf(1)))
            assertThrows(PortalIncompleteFrameException::class.java) { channel.receive(20) }
            assertThrows(IllegalStateException::class.java) { channel.receive(20) }
        }
    }

    @Test
    fun `stalled write cannot escape deadline`(
        @TempDir dir: Path,
    ) {
        connection(dir) { channel, _ ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit<Boolean> {
                    assertThrows(java.io.IOException::class.java) {
                        channel.send(PortalFrame(PortalKind.Request, 1, PortalMethod.Echo, PortalPayload(ByteArray(1024 * 1024)), 0), deadline = Deadline.afterMillis(40))
                    }
                    true
                }
                assertTrue(result.get(3, TimeUnit.SECONDS))
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `close waits for borrow and later uses reject`(
        @TempDir dir: Path,
    ) {
        Files.writeString(dir.resolve("grant"), "x")
        val capability = openGrantedRead(dir, "grant")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val borrowed = executor.submit {
                capability.borrow {
                entered.countDown()
                assertTrue(release.await(3, TimeUnit.SECONDS))
            }
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val closed = executor.submit {
                closing.countDown()
                capability.close()
            }
            assertTrue(closing.await(3, TimeUnit.SECONDS))
            assertFalse(closed.isDone)
            release.countDown()
            borrowed.get(3, TimeUnit.SECONDS)
            closed.get(3, TimeUnit.SECONDS)
            capability.close()
            assertThrows(IllegalStateException::class.java) { capability.borrow { fail<Unit>("used closed FD") } }
        } finally {
            release.countDown()
            executor.shutdownNow()
            capability.close()
        }
    }

    private fun connection(
        dir: Path,
        block: (PortalChannel, SocketChannel) -> Unit,
    ) {
        val path = dir.resolve("rpc.sock").toString()
        val server = RealSocketManager.createUnixServer(path)
        try {
            SocketChannel.open(UnixDomainSocketAddress.of(path)).use { peer ->
                PortalChannel(RealSocketManager.accept(server)).use { channel -> block(channel, peer) }
            }
        } finally {
            RealSocketManager.close(server)
        }
    }
}
