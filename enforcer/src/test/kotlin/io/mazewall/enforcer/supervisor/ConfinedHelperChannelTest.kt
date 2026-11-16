package io.mazewall.ffi.networking

import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.api.MockNativeNetworking
import io.mazewall.enforcer.internal.execution.HelperBinding
import io.mazewall.enforcer.internal.supervisor.*
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.CmsghdrSegment
import io.mazewall.ffi.memory.IovecSegment
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.MsghdrSegment
import io.mazewall.ffi.memory.native
import io.mazewall.ffi.memory.writeLong
import io.mazewall.ffi.networking.ConfinedDeadline
import io.mazewall.ffi.networking.ConfinedHelperChannel
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.fd.close
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.getFdOrThrow
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfinedHelperChannelTest {
    internal class Fixture(
        socketValue: Long = 0L,
        private val receivedFd: Int = 812,
    ) : AutoCloseable {
        var now = 1_000_000_000L
        var cancelled = false
        var healthy = true
        var sendLimit = Int.MAX_VALUE
        var receiveLimit = Int.MAX_VALUE
        var nextError = 0
        var unsolicited = false
        var receiveRightsAt: Int? = null
        var receivedBytes = 0
            private set
        var afterError: () -> Unit = {}
        var pollResult = 1L
        var afterReceive: () -> Unit = {}
        val incoming = ArrayDeque<Byte>()
        val outgoing = ByteArrayOutputStream()
        val requested = mutableListOf<Int>()
        val closed = mutableListOf<Int>()
        val polls = mutableListOf<Int>()
        private val networking = object : MockNativeNetworking() {
            init {
                socketResult = LinuxNative.SyscallResult.Success(socketValue)
            }

            override fun sendmsg(
                sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                msg: ManagedSegment,
                flags: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                assertTrue(flags == 0x40 || flags == 0x40 or 0x4000)
                if (nextError != 0) {
                    afterError()
                    return LinuxNative.SyscallResult.Error(nextError, -1L)
                }
                val header = MsghdrSegment(msg.native)
                val iov = IovecSegment(header.getMsgIov().reinterpret(Layouts.IOVEC.byteSize()))
                val count = minOf(iov.getIovLen().toInt(), sendLimit)
                outgoing.write(iov.getIovBase().reinterpret(count.toLong()).toArray(ValueLayout.JAVA_BYTE))
                now += 1_000_000L
                return LinuxNative.SyscallResult.Success(count.toLong())
            }

            override fun recvmsg(
                sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                msg: ManagedSegment,
                flags: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                assertEquals(NativeConstants.MSG_CMSG_CLOEXEC or 0x40, flags)
                if (nextError != 0) {
                    afterError()
                    return LinuxNative.SyscallResult.Error(nextError, -1L)
                }
                val header = MsghdrSegment(msg.native)
                assertEquals(24L, header.getMsgControllen())
                val iov = IovecSegment(header.getMsgIov().reinterpret(Layouts.IOVEC.byteSize()))
                requested += iov.getIovLen().toInt()
                val count = minOf(incoming.size, receiveLimit, iov.getIovLen().toInt())
                val data = iov.getIovBase().reinterpret(count.toLong())
                repeat(count) { data.set(ValueLayout.JAVA_BYTE, it.toLong(), incoming.removeFirst()) }
                if (unsolicited || receiveRightsAt == receivedBytes) {
                    val control = CmsghdrSegment(header.getMsgControl().reinterpret(24))
                    control.setCmsgLen(20)
                    control.setCmsgLevel(1)
                    control.setCmsgType(1)
                    control.setDataFd(receivedFd)
                    header.setMsgControllen(24)
                } else {
                    header.setMsgControllen(0)
                }
                receivedBytes += count
                now += 1_000_000L
                afterReceive()
                return LinuxNative.SyscallResult.Success(count.toLong())
            }
        }
        val native = MockNativeEngine(networking = networking).also { native ->
            native.onSyscall = { nr, id, buffer, _, _, _, _ ->
                assertEquals(Arch.current().clock_gettime.toLong(), nr)
                assertEquals(1L, id.asLong)
                (buffer as NativeArg.MemoryArg).value.writeLong(0, now / 1_000_000_000)
                buffer.value.writeLong(8, now % 1_000_000_000)
                LinuxNative.SyscallResult.Success(0L)
            }
            native.onPoll = { _, count, timeout ->
                assertEquals(1L, count)
                assertTrue(timeout in 1..25)
                polls += timeout
                now += timeout * 1_000_000L
                LinuxNative.SyscallResult.Success(pollResult)
            }
            native.fileSystem.onClose = { fd ->
                closed += fd.value
                LinuxNative.SyscallResult.Success(0L)
            }
        }
        val socket = native.networking.socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0).getFdOrThrow("mock socket", FileDescriptorRole.UnixSocket)
        val channel = ConfinedHelperChannel(native, socket, { cancelled }, { healthy })

        fun input(vararg bytes: Int) {
            bytes.forEach { incoming.addLast(it.toByte()) }
        }

        fun input(bytes: ByteArray) {
            bytes.forEach(incoming::addLast)
        }

        override fun close() {
            if (socket.isLiveForIo()) socket.close(native.fileSystem)
        }
    }

    @Test
    fun `partial progress stays bounded and receive never reads into next descriptor byte`() =
        Fixture().use { f ->
        f.input(1, 2, 3, 0)
        f.receiveLimit = 1
        assertContentEquals(byteArrayOf(1, 2, 3), f.channel.receive(3, ConfinedDeadline(2_000_000_000)))
        assertEquals(listOf(3, 2, 1), f.requested)
        assertEquals(listOf(0.toByte()), f.incoming.toList())
        f.sendLimit = 1
        f.channel.send(byteArrayOf(4, 5, 6), ConfinedDeadline(2_000_000_000))
        assertContentEquals(byteArrayOf(4, 5, 6), f.outgoing.toByteArray())
        assertTrue(f.socket.isLiveForIo())
    }

    @Test
    fun `coalesced frames stop exactly at requested boundary`() =
        Fixture().use { f ->
        f.input(1, 2, 3, 4)
        assertContentEquals(byteArrayOf(1, 2), f.channel.receive(2, ConfinedDeadline(2_000_000_000)))
        assertContentEquals(byteArrayOf(3, 4), f.channel.receive(2, ConfinedDeadline(2_000_000_000)))
        assertEquals(listOf(2, 2), f.requested)
    }

    @Test
    fun `stalled writes and repeated interrupts expire original deadline`() {
        for (errno in listOf(4, 11)) {
            Fixture().use { f ->
                f.nextError = errno
                assertFailsWith<TimeoutException> { f.channel.send(byteArrayOf(1), ConfinedDeadline(1_075_000_000)) }
                assertTrue(f.now <= 1_100_000_000)
                assertTrue(f.polls.size <= 4)
                assertTrue(f.outgoing.size() == 0 && f.socket.isLiveForIo())
            }
            Fixture().use { f ->
                f.nextError = errno
                assertFailsWith<TimeoutException> { f.channel.receive(15, ConfinedDeadline(1_075_000_000)) }
                assertTrue(f.now <= 1_100_000_000)
            }
        }
    }

    @Test
    fun `partial byte arrival cannot turn expired read into success`() =
        Fixture().use { f ->
        f.input(1, 2, 3)
        f.receiveLimit = 1
        f.afterReceive = { f.now += 40_000_000 }
        assertFailsWith<TimeoutException> { f.channel.receive(3, ConfinedDeadline(1_050_000_000)) }
        assertTrue(f.incoming.isNotEmpty())
    }

    @Test
    fun `unsolicited rights are owned and closed before rejection including late cancellation`() {
        for (cancel in listOf(false, true)) {
            Fixture().use { f ->
                f.input(1)
                f.unsolicited = true
                f.afterReceive = { f.cancelled = cancel }
                assertFailsWith<IOException> { f.channel.receive(1, ConfinedDeadline(2_000_000_000)) }
                assertEquals(listOf(812), f.closed)
                assertTrue(f.socket.isLiveForIo())
            }
        }
    }

    @Test
    fun `permission EOF cancellation and peer loss preserve terminal cause`() {
        for (errno in listOf(1, 13)) {
            Fixture().use { f ->
                f.nextError = errno
                val failure = assertFailsWith<ConfinedHelperRejection> { f.channel.receive(1, ConfinedDeadline(2_000_000_000)) }
                assertEquals(ConfinedRejectReason.Permission, failure.reason)
                assertEquals(errno, failure.errno)
            }
        }
        Fixture().use { f -> assertFailsWith<IOException> { f.channel.receive(1, ConfinedDeadline(2_000_000_000)) } }
        Fixture().use { f ->
            f.cancelled = true
            assertFailsWith<CancellationException> { f.channel.send(byteArrayOf(1), ConfinedDeadline(2_000_000_000)) }
            assertTrue(f.outgoing.size() == 0)
        }
        Fixture().use { f ->
            f.healthy = false
            assertFailsWith<IOException> { f.channel.send(byteArrayOf(1), ConfinedDeadline(2_000_000_000)) }
            assertTrue(f.outgoing.size() == 0)
        }
    }

    @Test
    fun `permission result is preserved even when the same call exhausts deadline`() =
        Fixture().use { f ->
        f.nextError = 13
        f.afterError = { f.now = 2_000_000_001 }
        val failure = assertFailsWith<ConfinedHelperRejection> { f.channel.receive(1, ConfinedDeadline(2_000_000_000)) }
        assertEquals(ConfinedRejectReason.Permission, failure.reason)
        assertEquals(13, failure.errno)
    }

    @Test
    fun `expected descriptor stays owned until caller closes it and timeout rolls back late receipt`() {
        Fixture().use { f ->
            f.unsolicited = true
            f.input(0)
            val owner = f.channel.receiveDescriptor(ConfinedDeadline(2_000_000_000))
            assertTrue(owner.handle.isLiveForIo())
            assertTrue(f.closed.isEmpty())
            owner.close()
            assertEquals(listOf(812), f.closed)
        }
        Fixture().use { f ->
            f.unsolicited = true
            f.input(0)
            f.afterReceive = { f.now = 2_000_000_001 }
            assertFailsWith<TimeoutException> { f.channel.receiveDescriptor(ConfinedDeadline(2_000_000_000)) }
            assertEquals(listOf(812), f.closed)
        }
    }

    @Test
    fun `descriptor byte must be zero and successful send does not close sender owner`() {
        Fixture().use { f ->
            f.unsolicited = true
            f.input(99)
            assertFailsWith<IOException> { f.channel.receiveDescriptor(ConfinedDeadline(2_000_000_000)) }
            assertEquals(listOf(812), f.closed)
        }
        Fixture().use { f ->
            f.native.networking.socketResult = LinuxNative.SyscallResult.Success(813L)
            val handle = f.native.networking
                .socket(1, 1, 0)
                .getFdOrThrow("mock transfer owner")
            SupervisorOwnedFd(handle, f.native.fileSystem).use { owner ->
                f.channel.sendDescriptor(owner, ConfinedDeadline(2_000_000_000))
                assertContentEquals(byteArrayOf(0), f.outgoing.toByteArray())
                assertTrue(owner.handle.isLiveForIo())
                assertTrue(f.closed.isEmpty())
            }
        }
    }

    @Test
    fun `header and payload share one cap while the offered deadline is echoed unchanged`() =
        Fixture().use { f ->
        val frame = controlFrame(ConfinedDeadline(5_000_000_000))
        val bytes = ConfinedHelperProtocol.encode(frame)
        f.input(bytes + byteArrayOf(0))
        val localCap = ConfinedDeadline(2_000_000_000)
        val received = f.channel.receiveFrame(localCap)
        assertEquals(frame, received.frame)
        assertEquals(localCap, received.localDeadline)
        assertEquals(listOf(15, 116), f.requested)
        assertEquals(listOf(0.toByte()), f.incoming.toList())
        f.channel.sendFrame(received.frame, received.localDeadline)
        assertContentEquals(bytes, f.outgoing.toByteArray())
    }

    @Test
    fun `single byte header progress cannot renew provisional cap`() =
        Fixture().use { f ->
        f.input(ConfinedHelperProtocol.encode(controlFrame(ConfinedDeadline(5_000_000_000))))
        f.receiveLimit = 1
        f.afterReceive = { f.now += 400_000_000 }
        assertFailsWith<TimeoutException> { f.channel.receiveFrame(ConfinedDeadline(2_000_000_000)) }
        assertTrue(f.requested.all { it <= 15 })
        assertTrue(f.incoming.size > 116)
    }

    @Test
    fun `expired distant and malformed offers refuse before payload reads and stay terminal`() {
        val good = ConfinedHelperProtocol.encode(controlFrame(ConfinedDeadline(2_000_000_000)))
        for (bad in listOf(
            good.copyOf().also { ByteBuffer.wrap(it).putLong(7, 1_000_000_001) },
            good.copyOf().also { ByteBuffer.wrap(it).putLong(7, 9_000_000_000) },
            good.copyOf().also { ByteBuffer.wrap(it).putInt(3, Int.MAX_VALUE) },
            good.copyOf().also { it[0] = 3 },
        )) {
            Fixture().use { f ->
                f.input(bad)
                kotlin.test.assertFails { f.channel.receiveFrame(ConfinedDeadline(3_000_000_000)) }
                assertEquals(listOf(15), f.requested)
                kotlin.test.assertFails { f.channel.sendFrame(controlFrame(ConfinedDeadline(3_000_000_000))) }
                assertEquals(0, f.outgoing.size())
            }
        }
    }

    @Test
    fun `idle polling returns to owner within one step and frames retain their origin`() =
        Fixture().use { f ->
        f.pollResult = 0
        assertEquals(null, f.channel.pollFrame())
        assertEquals(listOf(25), f.polls)
        assertTrue(f.requested.isEmpty())
        f.pollResult = 1
        val frame = controlFrame(ConfinedDeadline(2_000_000_000))
        f.input(ConfinedHelperProtocol.encode(frame))
        assertEquals(frame, f.channel.pollFrame()?.frame)
    }

    private fun controlFrame(deadline: ConfinedDeadline): ConfinedHelperProtocol.Frame =
        ConfinedHelperProtocol.Frame.Control(
        ConfinedHelperProtocol.ControlTag.Ready,
        ConfinedBinding(HelperBinding(Pid(7), 11, 13, "11".repeat(32), "22".repeat(32)), "33".repeat(32)),
        deadline,
    )
}
