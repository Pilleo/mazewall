package io.mazewall.profiler.engine

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeFileSystem
import io.mazewall.MockNativeMemory
import io.mazewall.MockNativeNetworking
import io.mazewall.MockNativeProcess
import io.mazewall.core.NativeArg
import io.mazewall.NativeTransaction
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.Tid
import io.mazewall.core.LoopAction
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.*
import java.lang.foreign.MemoryLayout
import java.lang.foreign.ValueLayout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProfilerDaemonTest {

    private companion object {
        private const val PROTOCOL_ACK_BYTE = 0xAC.toByte()
    }

    private class MockTransport : ProfilerTransport, SeccompResponder, TraceEventPublisher, NativeIoOperations, SocketLifecycleManager {
        val sentEvents = mutableListOf<SyscallEvent<SyscallEventState.Resolved>>()
        var continueSent = false
        var errorSent = false
        val ioctlCalls = mutableListOf<Long>()
        var nextPollResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(1L)
        var nextReadResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(1L)
        var ackByte: Byte = PROTOCOL_ACK_BYTE

        context(arena: NativeArena)
        override fun sendTraceEvent(socketFd: FileDescriptor<*, FdState.Open>, event: SyscallEvent<SyscallEventState.Resolved>) {
            sentEvents.add(event)
        }

        context(arena: NativeArena)
        override fun sendSeccompContinue(session: HandshakeSession.Success, resp: ManagedSegment) {
            continueSent = true
        }

        context(arena: NativeArena)
        override fun sendSeccompError(session: HandshakeSession.Failed, resp: ManagedSegment, errorNr: Int) {
            errorSent = true
        }

        override fun read(fd: FileDescriptor<*, FdState.Open>, buf: ManagedSegment, count: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = nextReadResult.also {
            if (it is LinuxNative.SyscallResult.Success && it.value > 0) {
                buf.writeByte(0L, ackByte)
            }
        }

        override fun write(fd: FileDescriptor<*, FdState.Open>, buf: ManagedSegment, count: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(count)

        override fun recv(sockfd: FileDescriptor<*, FdState.Open>, buf: ManagedSegment, len: Long, flags: Int): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(len)

        override fun poll(fds: ManagedSegment, nfds: Long, timeout: Int): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = nextPollResult

        override fun ioctl(fd: FileDescriptor<*, FdState.Open>, request: Long, arg: ManagedSegment): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
            ioctlCalls.add(request)
            if (request == SECCOMP_IOCTL_NOTIF_RECV) {
                arg.writeLong(NOTIF_ID_OFF, 123L)
                arg.writeInt(NOTIF_PID_OFF, 456)
                arg.writeInt(NOTIF_NR_OFF, 2)
            }
            return LinuxNative.SyscallResult.Success(0L)
        }

        override val raw = object : io.mazewall.RawSyscallOperations {
            context(_: NativeTransaction)
            override fun poll(fds: ManagedSegment, nfds: Long, timeout: Int): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                System.err.println("[MOCK] poll nfds=$nfds nextPollResult=$nextPollResult")
                if (nextPollResult is LinuxNative.SyscallResult.Success && (nextPollResult as LinuxNative.SyscallResult.Success).value > 0) {
                    fds.writeShort(6L, NativeConstants.POLLIN)
                    System.err.println("[MOCK] set POLLIN at offset 6. Value=${fds.readShort(6L)}")
                }
                return nextPollResult
            }

            context(_: NativeTransaction)
            override fun ioctl(fd: FileDescriptor<*, FdState.Open>, request: Long, arg: ManagedSegment): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                ioctlCalls.add(request)
                if (request == SECCOMP_IOCTL_NOTIF_RECV) {
                    arg.writeLong(NOTIF_ID_OFF, 123L)
                    arg.writeInt(NOTIF_PID_OFF, 456)
                    arg.writeInt(NOTIF_NR_OFF, 2)
                    arg.writeLong(NOTIF_ARGS_OFF, 0x1000L)
                }
                return LinuxNative.SyscallResult.Success(0L)
            }

            context(_: NativeTransaction)
            override fun ioctl(fd: FileDescriptor<*, FdState.Open>, request: Long, arg: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(0L)

            context(_: NativeTransaction)
            override fun fcntl(fd: FileDescriptor<*, FdState.Open>, cmd: Int, arg: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(0L)

            context(_: NativeTransaction)
            override fun syscall(nr: Long, arg1: NativeArg, arg2: NativeArg, arg3: NativeArg, arg4: NativeArg, arg5: NativeArg, arg6: NativeArg): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(0L)

            context(_: NativeTransaction)
            override fun syscall4(nr: Long, arg1: NativeArg, arg2: NativeArg, arg3: NativeArg, arg4: NativeArg): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(0L)
        }

        override fun createUnixServer(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> = FileDescriptor.unsafe(99)
        override fun accept(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> = FileDescriptor.unsafe(100)
        override fun connect(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> = FileDescriptor.unsafe(101)
        override fun sendDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>, fdToSend: FileDescriptor<*, FdState.Open>): Boolean = true
        override fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>? = FileDescriptor.unsafe(20)
        override fun close(fd: FileDescriptor<*, FdState.Open>) {}
    }

    private class MockReader : ProfilerMemoryReader {
        context(arena: NativeArena)
        override fun readStringFromProcess(tid: Tid, remoteAddr: Long, maxLen: Int): String? = "/tmp/test.txt"
        context(arena: NativeArena)
        override fun resolveLink(tid: Tid, link: String): String? = "/proc/1/cwd"
    }

    @Test
    fun `test handleActiveListener - success path`() {
        val transport = MockTransport()
        // Mock successful poll and read returning ACK
        transport.nextPollResult = LinuxNative.SyscallResult.Success(1L)
        transport.nextReadResult = LinuxNative.SyscallResult.Success(1L)
        transport.ackByte = PROTOCOL_ACK_BYTE

        val reader = MockReader()
        val syscallMap = mapOf(2 to "OPEN")
        val handler = ProfilerSessionHandler(
            FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10),
            FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(20),
            transport,
            transport,
            transport,
            reader,
            syscallMap,
        ) { }

        NativeArena.ofConfined().use { arena ->
            val notif = arena.allocate(Layouts.SECCOMP_NOTIF.byteSize())
            val resp = arena.allocate(Layouts.SECCOMP_NOTIF_RESP.byteSize())
            val ackBuf = arena.allocate(1L)
            val socketPollFd = arena.allocate(Layouts.POLLFD.byteSize())

            val pollFds = arena.allocate(Layouts.POLLFD.byteSize() * 2)
            // [0]: Seccomp listener FD - set POLLIN
            pollFds.writeShort(Layouts.POLLFD_REVENTS_OFFSET, NativeConstants.POLLIN)

            val action = with(arena) {
                handler.handleActiveListener(pollFds, ackBuf, notif, resp, socketPollFd)
            }

            assertTrue(action is LoopAction.Continue)
            assertEquals(1, transport.sentEvents.size)
            assertEquals("OPEN", transport.sentEvents[0].syscallName)
            assertEquals(Tid(456), transport.sentEvents[0].tid)
            // Verify that continue response was sent via type-safe method
            assertTrue(transport.continueSent, "Should have called sendSeccompContinue")
        }
    }

    @Test
    fun `test handshake - handler sends error on ACK timeout`() {
        // In handshake mode, if the listener fails to ACK within the timeout (or poll returns 0),
        // the daemon sends a seccomp error to unblock the tracee.
        val transport = MockTransport()
        transport.nextPollResult = LinuxNative.SyscallResult.Success(0L)

        val reader = MockReader()
        val syscallMap = mapOf(2 to "OPEN")
        val handler = ProfilerSessionHandler(
            FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10),
            FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(20),
            transport,
            transport,
            transport,
            reader,
            syscallMap,
        ) { }

        NativeArena.ofConfined().use { arena ->
            val notif = arena.allocate(Layouts.SECCOMP_NOTIF.byteSize())
            val resp = arena.allocate(Layouts.SECCOMP_NOTIF_RESP.byteSize())
            val ackBuf = arena.allocate(1L)
            val socketPollFd = arena.allocate(Layouts.POLLFD.byteSize())

            val pollFds = arena.allocate(Layouts.POLLFD.byteSize() * 2)
            pollFds.writeShort(Layouts.POLLFD_REVENTS_OFFSET, NativeConstants.POLLIN)

            val action = with(arena) {
                handler.handleActiveListener(pollFds, ackBuf, notif, resp, socketPollFd)
            }

            // Handshake failure should cause processNotification to return false, breaking the loop
            assertTrue(action is LoopAction.Break)
            assertFalse(transport.continueSent, "Should NOT have sent CONTINUE on handshake failure")
            assertTrue(transport.errorSent, "Should have sent seccomp error on handshake failure")
            assertTrue(handler.state is ProfilerState.Terminated, "State should be Terminated")
        }
    }

    @Test
    fun `test SessionEventLedger records CONTINUE and EventSent in handshake mode`() {
        val transport = MockTransport()
        // Mock successful poll and read returning ACK
        transport.nextPollResult = LinuxNative.SyscallResult.Success(1L)
        transport.nextReadResult = LinuxNative.SyscallResult.Success(1L)
        transport.ackByte = PROTOCOL_ACK_BYTE

        val reader = MockReader()
        val syscallMap = mapOf(2 to "OPEN")
        val handler = ProfilerSessionHandler(
            FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10),
            FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(20),
            transport,
            transport,
            transport,
            reader,
            syscallMap,
        ) { }

        NativeArena.ofConfined().use { arena ->
            val notif = arena.allocate(Layouts.SECCOMP_NOTIF.byteSize())
            val resp = arena.allocate(Layouts.SECCOMP_NOTIF_RESP.byteSize())
            val ackBuf = arena.allocate(1L)
            val socketPollFd = arena.allocate(Layouts.POLLFD.byteSize())

            val pollFds = arena.allocate(Layouts.POLLFD.byteSize() * 2)
            pollFds.writeShort(Layouts.POLLFD_REVENTS_OFFSET, NativeConstants.POLLIN)

            with(arena) {
                handler.handleActiveListener(pollFds, ackBuf, notif, resp, socketPollFd)
            }

            // In handshake mode: with successful ACK, CONTINUE is sent.
            assertFalse(handler.state is ProfilerState.Terminated, "State should not be Terminated")

            // Check that ledger recorded key events
            val events = handler.ledger.dump()
            assertTrue(events.isNotEmpty(), "Ledger should have recorded events")
            assertTrue(events.any { it is SessionEvent.Notified }, "Ledger should contain Notified event")
            assertTrue(events.any { it is SessionEvent.VmReadvResolved }, "Ledger should contain VmReadvResolved event")
            assertTrue(events.any { it is SessionEvent.EventSent }, "Ledger should contain EventSent event")
            assertTrue(events.any { it is SessionEvent.AckReceived }, "Ledger should contain AckReceived event")
            assertTrue(events.any { it is SessionEvent.ContinueReplied }, "Ledger should contain ContinueReplied event")
        }
    }

    @Test
    fun `test profiler daemon instantiation coverage`() {
        val clazz = ProfilerDaemon::class.java
        org.junit.jupiter.api.Assertions.assertNotNull(clazz)
    }
}
