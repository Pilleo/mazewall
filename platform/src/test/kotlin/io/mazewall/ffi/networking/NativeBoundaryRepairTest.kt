package io.mazewall.ffi.networking

import io.mazewall.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.internal.RealNativeEngine
import io.mazewall.ffi.memory.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.ForeignFdGuard
import io.mazewall.platform.lifecycle.OpenFlags
import io.mazewall.platform.lifecycle.TestFileDescriptors
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.NativeFileSystem
import io.mazewall.platform.native.NativeNetworking
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.socket.RealSocketManager
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(ForeignFdGuard::class)
class NativeBoundaryRepairTest {
    private val real = RealNativeEngine

    @Test
    fun `deadline descriptor transport rechecks readiness after EAGAIN without blocking`() =
        withPair { _, sender, receiver ->
        var attempts = 0
        var polls = 0
        val transport = object : NativeNetworking by real.networking {
            override fun sendmsg(
                sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                msg: ManagedSegment,
                flags: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                assertEquals(0x40, flags)
                if (++attempts == 1) return LinuxNative.SyscallResult.Error(NativeConstants.EAGAIN, -1)
                return real.networking.sendmsg(sockfd, msg, flags)
            }
        }
        assertTrue(SupervisorSocketUtils.sendDescriptor(sender.value, sender.value, transport) { polls++ })
        val received = requireNotNull(SupervisorSocketUtils.recvDescriptor(receiver))
        real.fileSystem.close(received).getOrThrow("close received")
        assertEquals(2, polls)
    }

    @Test
    fun `zero-byte descriptor send is not successful delivery`() =
        withPair { _, sender, _ ->
        val transport = object : NativeNetworking by real.networking {
            override fun sendmsg(
                sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                msg: ManagedSegment,
                flags: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(0)
        }
        assertFalse(SupervisorSocketUtils.sendDescriptor(sender.value, sender.value, transport) {})
    }

    private fun installNetworking(transport: NativeNetworking) {
        LinuxNative.setEngine(object : NativeEngine by real {
            override val networking: NativeNetworking = transport
        })
    }

    @AfterEach
    fun reset() {
        LinuxNative.resetToDefault()
    }

    @Test
    fun `O_PATH behavior is preserved in a fresh JVM`() {
        IsolatedProcessTester.runIsolatedMethod(
            javaClass.name,
            "O_PATH opens a descriptor that cannot read file contents",
        )
    }

    @Test
    fun `O_PATH opens a descriptor that cannot read file contents`() {
        NativeArena.ofConfined().use { arena ->
            val value = real.fileSystem.open(arena.allocateFrom("/dev/null"), OpenFlags(NativeConstants.O_PATH)).getOrThrow("open")
            val fd = TestFileDescriptors
                .adopt(value.toInt(), FileDescriptorRole.Generic)
            try {
                val result = real.memory.read(fd, arena.allocate(1), 1)
                assertTrue(result is LinuxNative.SyscallResult.Error && result.errno == NativeConstants.EBADF)
            } finally {
                real.fileSystem.close(fd).getOrThrow("close")
            }
        }
    }

    @Test
    fun `received descriptor is close on exec`() =
        withPair { arena, sender, receiver ->
        sendRights(arena, sender, intArrayOf(sender.value))
        val received = requireNotNull(SupervisorSocketUtils.recvDescriptor(receiver))
        try {
            assertEquals(1L, real.raw.fcntl(received, NativeConstants.F_GETFD, 0).getOrThrow("fcntl") and 1L)
        } finally {
            real.fileSystem.close(received).getOrThrow("close")
        }
    }

    @Test
    fun `two descriptors are rejected and both received copies closed`() = rejectRights(2)

    @Test
    fun `later fatal close failure is primary after all received descriptors are closed`() =
        withPair { arena, sender, receiver ->
            sendRights(arena, sender, intArrayOf(sender.value, sender.value))
            val ordinary = IllegalStateException("first close failed")
            val fatal = LinkageError("second close failed fatally")
            val closed = mutableListOf<Int>()
            val fileSystem = object : NativeFileSystem by real.fileSystem {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    real.fileSystem.close(fd).getOrThrow("close received fixture")
                    closed.add(fd.value)
                    if (closed.size == 1) throw ordinary
                    throw fatal
                }
            }
            LinuxNative.setEngine(object : NativeEngine by real {
                override val fileSystem: NativeFileSystem = fileSystem
            })
            val thrown = assertThrows<Throwable> { SupervisorSocketUtils.recvDescriptor(receiver) }
            assertEquals(2, closed.size, "both independent closes must be attempted")
            assertEquals(2, closed.toSet().size, "each received descriptor must close once")
            closed.forEach { assertFalse(LinuxNative.isDescriptorLive(it)) }
            assertSame(fatal, thrown, "fatal JVM failure must not be hidden under an ordinary failure")
            assertEquals(listOf(ordinary), thrown.suppressed.toList())
        }

    @Test
    fun `truncated descriptor message closes all delivered copies`() = rejectRights(3)

    @Test
    fun `malformed rights length rejects and closes complete received descriptor`() = rejectRights(1, malformed = true)

    @Test
    fun `EINTR is retried before receiving a descriptor`() =
        withPair { arena, sender, receiver ->
        sendRights(arena, sender, intArrayOf(sender.value))
        var calls = 0
        val networking = object : NativeNetworking by real.networking {
            override fun recvmsg(
                sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                msg: ManagedSegment,
                flags: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                if (calls++ == 0) return LinuxNative.SyscallResult.Error(NativeConstants.EINTR, -1)
                return real.networking.recvmsg(sockfd, msg, flags)
            }
        }
        installNetworking(networking)
        val received = requireNotNull(SupervisorSocketUtils.recvDescriptor(receiver))
        try {
            assertEquals(2, calls)
        } finally {
            real.fileSystem.close(received).getOrThrow("close")
        }
    }

    @Test
    fun `ordinary message without ancillary rights is rejected`() =
        withPair { arena, sender, receiver ->
        real.memory.write(sender, arena.allocate(1), 1).getOrThrow("write")
        assertNull(SupervisorSocketUtils.recvDescriptor(receiver))
    }

    @Test
    fun `overlong address is rejected before socket acquisition`() {
        var opens = 0
        val networking = object : NativeNetworking by real.networking {
            override fun socket(
                domain: Int,
                type: Int,
                protocol: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                opens++
                throw AssertionError("socket must not be acquired")
            }
        }
        installNetworking(networking)
        assertThrows<IllegalArgumentException> { RealSocketManager.createUnixServer("x".repeat(108)) }
        assertEquals(0, opens)
    }

    @Test
    fun `throwing bind and listen close the acquired socket`() {
        for (failAtBind in listOf(true, false)) {
            var opened = -1
            val failure = IllegalStateException("injected acquisition failure")
            val networking = object : NativeNetworking by real.networking {
                override fun socket(
                    domain: Int,
                    type: Int,
                    protocol: Int,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = real.networking.socket(domain, type, protocol).also { opened = it.getOrThrow("socket").toInt() }

                override fun bind(
                    sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    addr: ManagedSegment,
                    addrlen: Int,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    if (failAtBind) throw failure
                    return LinuxNative.SyscallResult.Success(0)
                }

                override fun listen(
                    sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    backlog: Int,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = throw failure
            }
            installNetworking(networking)
            try {
                assertSame(failure, assertThrows<IllegalStateException> { RealSocketManager.createUnixServer("/unused") })
                assertFalse(LinuxNative.isDescriptorLive(opened), "acquired socket leaked")
            } finally {
                if (LinuxNative.isDescriptorLive(opened)) {
                    real.fileSystem.close(
                        TestFileDescriptors
                    .adopt(opened, FileDescriptorRole.UnixSocket),
                    )
                }
            }
        }
    }

    private fun rejectRights(
        count: Int,
        malformed: Boolean = false,
    ) = withPair { arena, sender, receiver ->
        sendRights(arena, sender, IntArray(count) { sender.value })
        val delivered = mutableListOf<Int>()
        val networking = object : NativeNetworking by real.networking {
            override fun recvmsg(
                sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                msg: ManagedSegment,
                flags: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                val result = real.networking.recvmsg(sockfd, msg, flags)
                result.getOrThrow("recvmsg")
                val header = MsghdrSegment(msg.native)
                val control = header.getMsgControl().reinterpret(header.getMsgControllen())
                val cmsg = CmsghdrSegment(control)
                for (i in 0 until ((cmsg.getCmsgLen() - 16) / 4).toInt()) {
                    delivered.add(control.readInt(16L + i * 4))
                }
                if (malformed) cmsg.setCmsgLen(21)
                return result
            }
        }
        installNetworking(networking)
        try {
            assertNull(SupervisorSocketUtils.recvDescriptor(receiver))
            assertEquals(minOf(count, 2), delivered.size)
            delivered.forEach { assertFalse(LinuxNative.isDescriptorLive(it), "received fd $it leaked") }
        } finally {
            delivered.forEach {
                if (LinuxNative.isDescriptorLive(it)) {
                    real.fileSystem.close(
                        TestFileDescriptors
                    .adopt(it, FileDescriptorRole.Generic),
                    )
                }
            }
        }
    }

    private fun withPair(
        block: (NativeArena, FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>, FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>) -> Unit,
    ) {
        NativeArena.ofConfined().use { arena ->
            val pair = arena.allocate(8)
            real.networking.socketpair(1, 1 or NativeConstants.SOCK_CLOEXEC, 0, pair).getOrThrow("socketpair")
            val first = TestFileDescriptors
                .adopt(pair.readInt(0), FileDescriptorRole.UnixSocket)
            val second = TestFileDescriptors
                .adopt(pair.readInt(4), FileDescriptorRole.UnixSocket)
            try {
                block(arena, first, second)
            } finally {
                real.fileSystem.close(first).getOrThrow("close")
                real.fileSystem.close(second).getOrThrow("close")
            }
        }
    }

    private fun sendRights(
        arena: NativeArena,
        sender: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        descriptors: IntArray,
    ) {
        val length = 16L + descriptors.size * 4
        val control = arena.allocate((length + 7) and -8L)
        val cmsg = CmsghdrSegment(control.native)
        cmsg.setCmsgLen(length)
        cmsg.setCmsgLevel(1)
        cmsg.setCmsgType(1)
        descriptors.forEachIndexed { index, fd -> control.writeInt(16L + index * 4, fd) }
        val iov = IovecSegment(arena.allocate(Layouts.IOVEC).native)
        iov.setIovBase(arena.allocate(1).native)
        iov.setIovLen(1)
        val msg = MsghdrSegment(arena.allocate(Layouts.MSGHDR).native)
        msg.setMsgIov(iov.segment)
        msg.setMsgIovlen(1)
        msg.setMsgControl(control.native)
        msg.setMsgControllen(length)
        real.networking.sendmsg(sender, msg.managed, 0).getOrThrow("sendmsg")
    }
}
