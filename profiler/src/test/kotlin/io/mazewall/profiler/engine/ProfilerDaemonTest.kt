package io.mazewall.profiler.engine

import io.mazewall.LinuxNative
import io.mazewall.core.Pid
import io.mazewall.core.Tid
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.profiler.engine.SyscallEvent
import io.mazewall.profiler.engine.SyscallEventState
import io.mazewall.profiler.engine.TraceEvent
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
        val pollCount = AtomicInteger(0)
        var nextPollResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(1L)
        var nextReadResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(1L)
        var ackByte: Byte = 0xAC.toByte()
        val ioctlCalls = mutableListOf<Long>()
        var continueSent = false
        var errorSent = false
        var lastErrorNr = 0

        override fun sendTraceEvent(
            socketFd: FileDescriptor<*, FdState.Open>,
            event: SyscallEvent<SyscallEventState.Resolved>,
        ) {
            sentEvents.add(event)
        }

        override fun sendSeccompContinue(
            notifId: Long,
            listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
            resp: MemorySegment,
        ) {
            continueSent = true
            ioctlCalls.add(SECCOMP_IOCTL_NOTIF_SEND)
        }

        override fun sendSeccompError(
            notifId: Long,
            listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
            resp: MemorySegment,
            errorNr: Int,
        ) {
            errorSent = true
            lastErrorNr = errorNr
            ioctlCalls.add(SECCOMP_IOCTL_NOTIF_SEND)
        }

        override fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>? = FileDescriptor.unsafe(5)

        override fun poll(
            fds: MemorySegment,
            nfds: Long,
            timeout: Int,
        ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
            pollCount.incrementAndGet()
            // In the wait-for-ack loop, we need to set the revents
            if (nfds == 1L) {
                fds.set(ValueLayout.JAVA_SHORT, POLLFD_REVENTS_OFF, NativeConstants.POLLIN)
            }
            return nextPollResult
        }

        override fun read(
            fd: FileDescriptor<*, FdState.Open>,
            buf: MemorySegment,
            count: Long,
        ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
            if (count == 1L) {
                buf.set(ValueLayout.JAVA_BYTE, 0L, ackByte)
            }
            return nextReadResult
        }

        override fun write(
            fd: FileDescriptor<*, FdState.Open>,
            buf: MemorySegment,
            count: Long,
        ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(count)

        override fun recv(
            sockfd: FileDescriptor<*, FdState.Open>,
            buf: MemorySegment,
            len: Long,
            flags: Int,
        ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(len)

        override fun ioctl(
            fd: FileDescriptor<*, FdState.Open>,
            request: Long,
            arg: MemorySegment,
        ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
            ioctlCalls.add(request)
            if (request == 0xc0502100L) { // RECV
                arg.set(ValueLayout.JAVA_LONG, 0L, 123L) // id
                arg.set(ValueLayout.JAVA_INT, 8L, 456) // pid
                arg.set(ValueLayout.JAVA_INT, 16L, 2) // nr (open)
                arg.set(ValueLayout.JAVA_LONG, 32L, 0x1000L) // args[0] = non-zero pointer
            }
            return LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
        }

        override fun createServer(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> = FileDescriptor.unsafe(99)

        override fun accept(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> = FileDescriptor.unsafe(100)

        override fun close(fd: FileDescriptor<*, FdState.Open>) {}
    }

    private class MockReader : ProfilerMemoryReader {
        override fun readStringFromProcess(
            tid: Tid,
            remoteAddr: Long,
            maxLen: Int,
        ): String? = "/tmp/test.txt"

        override fun resolveLink(
            tid: Tid,
            link: String,
        ): String? = "/proc/1/cwd"
    }

    @Test
    fun `session handler processes notification and waits for ACK`() {
        val transport = MockTransport()
        val reader = MockReader()
        val syscallMap = mapOf(2 to "OPEN")
        var shutdownCalled = false
        val handler = ProfilerSessionHandler(
            FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10),
            FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(20),
            transport,
            transport,
            transport,
            reader,
            syscallMap,
        ) {
            shutdownCalled = true
        }

        Arena.ofConfined().use { arena ->
            val notif = arena.allocate(Layouts.SECCOMP_NOTIF)
            notif.set(ValueLayout.JAVA_LONG, NOTIF_ID_OFF, 123L) // ID
            notif.set(ValueLayout.JAVA_INT, NOTIF_PID_OFF, 456) // PID
            notif.set(ValueLayout.JAVA_INT, NOTIF_NR_OFF, 2) // NR (open)

            val resp = arena.allocate(Layouts.SECCOMP_NOTIF_RESP)
            val ackBuf = arena.allocate(1L)
            val socketPollFd = arena.allocate(Layouts.POLLFD)

            val pollFds = setupMockPoll(arena)
            val action = handler.handleActiveListener(pollFds, ackBuf, notif, resp, socketPollFd)
 
            assertTrue(action is LoopAction.Continue)
            assertEquals(1, transport.sentEvents.size)
            assertEquals("OPEN", transport.sentEvents[0].syscallName)
            assertEquals(Tid(456), transport.sentEvents[0].tid)
            // Verify that continue response was sent via type-safe method
            assertTrue(transport.continueSent, "Should have called sendSeccompContinue")
            assertTrue(transport.ioctlCalls.contains(SECCOMP_IOCTL_NOTIF_SEND), "Should have sent SECCOMP_IOCTL_NOTIF_SEND")
        }
    }
    @Test
    fun `test SessionEventLedger records CONTINUE and EventSent in fire-and-forget mode`() {
        val transport = MockTransport()
        val reader = MockReader()
        val syscallMap = mapOf(2 to "OPEN")
        var shutdownCalled = false
        val handler = ProfilerSessionHandler(
            FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10),
            FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(20),
            transport,
            transport,
            transport,
            reader,
            syscallMap,
        ) {
            shutdownCalled = true
        }

        Arena.ofConfined().use { arena ->
            val notif = arena.allocate(Layouts.SECCOMP_NOTIF)
            notif.set(ValueLayout.JAVA_LONG, NOTIF_ID_OFF, 123L) // ID
            notif.set(ValueLayout.JAVA_INT, NOTIF_PID_OFF, 456) // PID
            notif.set(ValueLayout.JAVA_INT, NOTIF_NR_OFF, 2) // NR (open)
            notif.set(ValueLayout.JAVA_LONG, NOTIF_ARGS_OFF, 0x1000L) // args[0] = non-zero pointer

            val resp = arena.allocate(Layouts.SECCOMP_NOTIF_RESP)
            val ackBuf = arena.allocate(1L)
            val socketPollFd = arena.allocate(Layouts.POLLFD)

            val pollFds = setupMockPoll(arena)
            handler.handleActiveListener(pollFds, ackBuf, notif, resp, socketPollFd)

            assertFalse(handler.state is ProfilerState.Terminated, "State should not be Terminated")

            // Check that ledger recorded key events
            val events = handler.ledger.dump()
            assertTrue(events.isNotEmpty(), "Ledger should have recorded events")
            assertTrue(events.any { it is SessionEvent.Notified }, "Ledger should contain Notified event")
            assertTrue(events.any { it is SessionEvent.VmReadvResolved }, "Ledger should contain VmReadvResolved event")
            assertTrue(events.any { it is SessionEvent.EventSent }, "Ledger should contain EventSent event")
            assertTrue(events.any { it is SessionEvent.ContinueReplied }, "Ledger should contain ContinueReplied event")
            assertFalse(events.any { it is SessionEvent.ErrorReplied }, "Ledger should NOT contain ErrorReplied")
        }
    }

    private fun setupMockPoll(arena: Arena): MemorySegment {
        val pollFds = arena.allocate(MemoryLayout.sequenceLayout(2, Layouts.POLLFD))
        // [0]: Seccomp listener FD - set POLLIN
        pollFds.set(ValueLayout.JAVA_SHORT, POLLFD_REVENTS_OFF, NativeConstants.POLLIN)
        return pollFds
    }
}
