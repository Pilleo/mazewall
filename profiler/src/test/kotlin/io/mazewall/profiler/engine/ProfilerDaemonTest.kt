package io.mazewall.profiler.engine

import io.mazewall.LinuxNative
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.Tid
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.atomic.AtomicInteger

class ProfilerDaemonTest {
    private class MockTransport : ProfilerTransport {
        val sentEvents = mutableListOf<SyscallEvent<SyscallEventState.Resolved>>()
        var nextPollResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(1L)
        var nextReadResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(1L)
        var ackByte: Byte = 0xAC.toByte()
        var continueSent = false
        var errorSent = false
        var lastErrorNr = 0
        val ioctlCalls = mutableListOf<Long>()

        context(arena: Arena)
        override fun sendTraceEvent(socketFd: FileDescriptor<*, FdState.Open>, event: SyscallEvent<SyscallEventState.Resolved>) {
            sentEvents.add(event)
        }

        context(arena: Arena)
        override fun sendSeccompContinue(session: HandshakeSession.Success, resp: MemorySegment) {
            continueSent = true
            ioctlCalls.add(NativeConstants.SECCOMP_IOCTL_NOTIF_SEND)
        }

        context(arena: Arena)
        override fun sendSeccompError(session: HandshakeSession.Failed, resp: MemorySegment, errorNr: Int) {
            errorSent = true
            lastErrorNr = errorNr
            ioctlCalls.add(NativeConstants.SECCOMP_IOCTL_NOTIF_SEND)
        }

        override fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>? = FileDescriptor.unsafe(5)
        override fun read(fd: FileDescriptor<*, FdState.Open>, buf: MemorySegment, count: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
            if (count == 1L) buf.set(ValueLayout.JAVA_BYTE, 0L, ackByte)
            return nextReadResult
        }
        override fun write(fd: FileDescriptor<*, FdState.Open>, buf: MemorySegment, count: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(count)
        override fun recv(sockfd: FileDescriptor<*, FdState.Open>, buf: MemorySegment, len: Long, flags: Int): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(len)
        override fun poll(fds: MemorySegment, nfds: Long, timeout: Int): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = nextPollResult
        override fun ioctl(fd: FileDescriptor<*, FdState.Open>, request: Long, arg: MemorySegment): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(0L)

        override val raw: io.mazewall.RawSyscallOperations = object : io.mazewall.MockNativeEngine() {
            context(context: io.mazewall.NativeTransaction)
            override fun poll(fds: MemorySegment, nfds: Long, timeout: Int): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                if (nfds == 1L) {
                    fds.set(ValueLayout.JAVA_SHORT, 6L, NativeConstants.POLLIN)
                }
                return nextPollResult
            }

            context(context: io.mazewall.NativeTransaction)
            override fun ioctl(fd: FileDescriptor<*, FdState.Open>, request: Long, arg: MemorySegment): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                ioctlCalls.add(request)
                if (request == NativeConstants.SECCOMP_IOCTL_NOTIF_RECV) {
                    arg.set(ValueLayout.JAVA_LONG, 0L, 123L) // id
                    arg.set(ValueLayout.JAVA_INT, 8L, 456) // pid
                    arg.set(ValueLayout.JAVA_INT, 16L, 2) // nr
                }
                return LinuxNative.SyscallResult.Success(0L)
            }
        }

        override fun createUnixServer(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> = FileDescriptor.unsafe(99)
        override fun accept(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> = FileDescriptor.unsafe(100)
        override fun connect(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> = FileDescriptor.unsafe(101)
        override fun sendDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>, fdToSend: FileDescriptor<*, FdState.Open>): Boolean = true
        override fun close(fd: FileDescriptor<*, FdState.Open>) {}
    }

    private class MockReader : ProfilerMemoryReader {
        context(arena: Arena)
        override fun readStringFromProcess(tid: Tid, remoteAddr: Long, maxLen: Int): String? = "/tmp/test.txt"
        context(arena: Arena)
        override fun resolveLink(tid: Tid, link: String): String? = "/proc/1/cwd"
    }

    @Test
    fun `session handler processes notification and waits for ACK`() {
        val transport = MockTransport()
        val reader = MockReader()
        val syscallMap = mapOf(2 to "OPEN")
        val handler = ProfilerSessionHandler(FileDescriptor.unsafe(10), FileDescriptor.unsafe(20), transport, transport, transport, reader, syscallMap) { }

        Arena.ofConfined().use { arena ->
            val notif = arena.allocate(Layouts.SECCOMP_NOTIF)
            val resp = arena.allocate(Layouts.SECCOMP_NOTIF_RESP)
            val ackBuf = arena.allocate(1L)
            val socketPollFd = arena.allocate(Layouts.POLLFD)
            val pollFds = arena.allocate(MemoryLayout.sequenceLayout(2, Layouts.POLLFD))
            pollFds.set(ValueLayout.JAVA_SHORT, 6L, NativeConstants.POLLIN)

            val action = with(arena) { handler.handleActiveListener(pollFds, ackBuf, notif, resp, socketPollFd) }
            assertTrue(action is LoopAction.Continue)
            assertEquals(1, transport.sentEvents.size)
            assertTrue(transport.continueSent)
        }
    }

    @Test
    fun `test handshake - handler sends error on ACK timeout`() {
        val transport = MockTransport()
        transport.nextPollResult = LinuxNative.SyscallResult.Success(0L)
        val reader = MockReader()
        val handler = ProfilerSessionHandler(FileDescriptor.unsafe(10), FileDescriptor.unsafe(20), transport, transport, transport, reader, emptyMap()) { }

        Arena.ofConfined().use { arena ->
            val notif = arena.allocate(Layouts.SECCOMP_NOTIF)
            val resp = arena.allocate(Layouts.SECCOMP_NOTIF_RESP)
            val ackBuf = arena.allocate(1L)
            val socketPollFd = arena.allocate(Layouts.POLLFD)
            val pollFds = arena.allocate(MemoryLayout.sequenceLayout(2, Layouts.POLLFD))
            pollFds.set(ValueLayout.JAVA_SHORT, 6L, NativeConstants.POLLIN)

            val action = with(arena) { handler.handleActiveListener(pollFds, ackBuf, notif, resp, socketPollFd) }
            assertTrue(action is LoopAction.Break)
            assertTrue(transport.errorSent)
        }
    }
}
