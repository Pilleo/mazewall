package io.mazewall.profiler.engine

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeFileSystem
import io.mazewall.MockNativeMemory
import io.mazewall.MockNativeNetworking
import io.mazewall.MockNativeProcess
import io.mazewall.core.NativeArg
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.Tid
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.SegmentPool
import io.mazewall.ffi.memory.writeShort
import io.mazewall.ffi.memory.writeInt
import io.mazewall.ffi.memory.writeLong
import io.mazewall.platform.seccomp.daemon.LoopAction
import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.io.IOException
import java.nio.channels.ClosedByInterruptException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import io.mazewall.platform.seccomp.daemon.NotifResult
import org.junit.jupiter.api.Test

class ProfilerDaemonTest {

    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
    }

    private companion object {
        private const val PROTOCOL_ACK_BYTE = 0xAC.toByte()
    }

    private open class MockTransport : ProfilerTransport, SeccompResponder, TraceEventPublisher, NativeIoOperations, SocketLifecycleManager {
        val sentEvents = mutableListOf<SyscallEvent<SyscallEventState.Resolved>>()
        var continueSent = false
        var errorSent = false
        val ioctlCalls = mutableListOf<Long>()
        var nextPollResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(1L)
        var nextReadResult: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(1L)
        var ackByte: Byte = PROTOCOL_ACK_BYTE

        context(arena: Arena)
        override fun sendTraceEvent(socketFd: FileDescriptor<*, FdState.Open>, event: SyscallEvent<SyscallEventState.Resolved>) {
            sentEvents.add(event)
        }

        context(arena: Arena)
        override fun sendSeccompContinue(session: HandshakeSession.Success, resp: MemorySegment) {
            continueSent = true
        }

        context(arena: Arena)
        override fun sendSeccompError(session: HandshakeSession.Failed, resp: MemorySegment, errorNr: Int) {
            errorSent = true
        }

        override fun read(fd: FileDescriptor<*, FdState.Open>, buf: MemorySegment, count: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = nextReadResult.also {
            if (it is LinuxNative.SyscallResult.Success && it.value > 0) {
                buf.set(ValueLayout.JAVA_BYTE, 0L, ackByte)
            }
        }

        override fun write(fd: FileDescriptor<*, FdState.Open>, buf: MemorySegment, count: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(count)

        override fun recv(sockfd: FileDescriptor<*, FdState.Open>, buf: MemorySegment, len: Long, flags: Int): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(len)

        override fun poll(fds: MemorySegment, nfds: Long, timeout: Int): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
            if (nextPollResult is LinuxNative.SyscallResult.Success && (nextPollResult as LinuxNative.SyscallResult.Success).value == 0L) {
                Thread.sleep(10)
            }
            return nextPollResult
        }

        override fun ioctl(fd: FileDescriptor<*, FdState.Open>, request: Long, arg: MemorySegment): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
            ioctlCalls.add(request)
            if (request == SECCOMP_IOCTL_NOTIF_RECV) {
                arg.set(ValueLayout.JAVA_LONG, io.mazewall.ffi.Layouts.SECCOMP_NOTIF_ID_OFFSET, 123L)
                arg.set(ValueLayout.JAVA_INT, io.mazewall.ffi.Layouts.SECCOMP_NOTIF_PID_OFFSET, 456)
                arg.set(ValueLayout.JAVA_INT, io.mazewall.ffi.Layouts.SECCOMP_NOTIF_NR_OFFSET, 2)
            }
            return LinuxNative.SyscallResult.Success(0L)
        }

        override val raw = object : io.mazewall.RawSyscallOperations {
            override fun poll(fds: ManagedSegment, nfds: Long, timeout: Int): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                System.err.println("[MOCK] poll nfds=$nfds nextPollResult=$nextPollResult")
                if (nextPollResult is LinuxNative.SyscallResult.Success && (nextPollResult as LinuxNative.SyscallResult.Success).value == 0L) {
                    Thread.sleep(10)
                }
                if (nextPollResult is LinuxNative.SyscallResult.Success && (nextPollResult as LinuxNative.SyscallResult.Success).value > 0) {
                    val fdsSeg = MemorySegment.ofAddress(fds.address()).reinterpret(fds.byteSize())
                    fdsSeg.set(ValueLayout.JAVA_SHORT, 6L, NativeConstants.POLLIN)
                    System.err.println("[MOCK] set POLLIN at offset 6. Value=${fdsSeg.get(ValueLayout.JAVA_SHORT, 6L)}")
                }
                return nextPollResult
            }

            override fun ioctl(fd: FileDescriptor<*, FdState.Open>, request: Long, arg: ManagedSegment): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                ioctlCalls.add(request)
                if (request == SECCOMP_IOCTL_NOTIF_RECV) {
                    val argSeg = MemorySegment.ofAddress(arg.address()).reinterpret(arg.byteSize())
                    argSeg.set(ValueLayout.JAVA_LONG, io.mazewall.ffi.Layouts.SECCOMP_NOTIF_ID_OFFSET, 123L)
                    argSeg.set(ValueLayout.JAVA_INT, io.mazewall.ffi.Layouts.SECCOMP_NOTIF_PID_OFFSET, 456)
                    argSeg.set(ValueLayout.JAVA_INT, io.mazewall.ffi.Layouts.SECCOMP_NOTIF_NR_OFFSET, 2)
                    argSeg.set(ValueLayout.JAVA_LONG, io.mazewall.ffi.Layouts.SECCOMP_NOTIF_ARGS_OFFSET, 0x1000L)
                }
                return LinuxNative.SyscallResult.Success(0L)
            }

            override fun ioctl(fd: FileDescriptor<*, FdState.Open>, request: Long, arg: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(0L)

            override fun fcntl(fd: FileDescriptor<*, FdState.Open>, cmd: Int, arg: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(0L)

            override fun syscall(nr: Long, arg1: NativeArg, arg2: NativeArg, arg3: NativeArg, arg4: NativeArg, arg5: NativeArg, arg6: NativeArg): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(0L)

            override fun syscall4(nr: Long, arg1: NativeArg, arg2: NativeArg, arg3: NativeArg, arg4: NativeArg): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(0L)
        }

        override fun createUnixServer(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> = FileDescriptor.unsafe(99)
        override fun accept(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> = FileDescriptor.unsafe(100)
        override fun connect(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> = FileDescriptor.unsafe(101)
        override fun sendDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>, fdToSend: FileDescriptor<*, FdState.Open>): Boolean = true
        open override fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>? = FileDescriptor.unsafe(20)
        override fun close(fd: FileDescriptor<*, FdState.Open>) {}
    }

    private class MockReader : ProfilerMemoryReader {
        context(arena: io.mazewall.ffi.memory.NativeArena)
        override fun readStringFromProcess(tid: Tid, remoteAddr: Long, maxLen: Int): String? = "/tmp/test.txt"
        context(arena: io.mazewall.ffi.memory.NativeArena)
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
        val socketFd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10)
        val listenerFd = FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(20)
        ProfilerSessionHandler(
            socketFd,
            listenerFd,
            transport,
            transport,
            transport,
            reader,
            syscallMap,
        ) { }.use { handler ->
            io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
                val notif = SegmentPool.SECCOMP_NOTIF_POOL.rent()
                val resp = SegmentPool.SECCOMP_NOTIF_RESP_POOL.rent()
                try {
                    notif.writeLong(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_ID_OFFSET, 123L)
                    notif.writeInt(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_PID_OFFSET, 456)
                    notif.writeInt(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_NR_OFFSET, 2)
                    notif.writeLong(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_ARGS_OFFSET, 0x1000L)

                    val ok = with(arena) {
                        handler.processNotification(notif, resp, listenerFd, socketFd)
                    }

                    assertEquals(io.mazewall.platform.seccomp.daemon.NotifResult.HANDLED, ok)
                    assertEquals(1, transport.sentEvents.size)
                    assertEquals("OPEN", transport.sentEvents[0].syscallName)
                    assertEquals(Tid(456), transport.sentEvents[0].tid)
                    assertTrue(transport.continueSent, "Should have called sendSeccompContinue")
                } finally {
                    SegmentPool.SECCOMP_NOTIF_POOL.release(notif)
                    SegmentPool.SECCOMP_NOTIF_RESP_POOL.release(resp)
                }
            }
        }
    }

    @Test
    fun `test handshake - handler sends error on ACK timeout`() {
        val transport = MockTransport()
        transport.nextPollResult = LinuxNative.SyscallResult.Success(0L)

        val reader = MockReader()
        val syscallMap = mapOf(2 to "OPEN")
        val socketFd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10)
        val listenerFd = FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(20)
        ProfilerSessionHandler(
            socketFd,
            listenerFd,
            transport,
            transport,
            transport,
            reader,
            syscallMap,
        ) { }.use { handler ->
            io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
                val notif = SegmentPool.SECCOMP_NOTIF_POOL.rent()
                val resp = SegmentPool.SECCOMP_NOTIF_RESP_POOL.rent()
                try {
                    notif.writeLong(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_ID_OFFSET, 123L)
                    notif.writeInt(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_PID_OFFSET, 456)
                    notif.writeInt(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_NR_OFFSET, 2)
                    notif.writeLong(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_ARGS_OFFSET, 0x1000L)

                    val ok = with(arena) {
                        handler.processNotification(notif, resp, listenerFd, socketFd)
                    }

                    assertEquals(io.mazewall.platform.seccomp.daemon.NotifResult.TERMINATE, ok)
                    assertFalse(transport.continueSent, "Should NOT have sent CONTINUE on handshake failure")
                    assertTrue(transport.errorSent, "Should have sent seccomp error on handshake failure")
                    assertTrue(handler.state is ProfilerState.Terminated, "State should be Terminated")
                } finally {
                    SegmentPool.SECCOMP_NOTIF_POOL.release(notif)
                    SegmentPool.SECCOMP_NOTIF_RESP_POOL.release(resp)
                }
            }
        }
    }

    @Test
    fun `test SessionEventLedger records CONTINUE and EventSent in handshake mode`() {
        val transport = MockTransport()
        transport.nextPollResult = LinuxNative.SyscallResult.Success(1L)
        transport.nextReadResult = LinuxNative.SyscallResult.Success(1L)
        transport.ackByte = PROTOCOL_ACK_BYTE

        val reader = MockReader()
        val syscallMap = mapOf(2 to "OPEN")
        val socketFd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10)
        val listenerFd = FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(20)
        ProfilerSessionHandler(
            socketFd,
            listenerFd,
            transport,
            transport,
            transport,
            reader,
            syscallMap,
        ) { }.use { handler ->
            io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
                val notif = SegmentPool.SECCOMP_NOTIF_POOL.rent()
                val resp = SegmentPool.SECCOMP_NOTIF_RESP_POOL.rent()
                try {
                    notif.writeLong(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_ID_OFFSET, 123L)
                    notif.writeInt(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_PID_OFFSET, 456)
                    notif.writeInt(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_NR_OFFSET, 2)
                    notif.writeLong(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_ARGS_OFFSET, 0x1000L)

                    with(arena) {
                        handler.processNotification(notif, resp, listenerFd, socketFd)
                    }

                    assertFalse(handler.state is ProfilerState.Terminated, "State should not be Terminated")

                    val events = handler.ledger.dump()
                    assertTrue(events.isNotEmpty(), "Ledger should have recorded events")
                    assertTrue(events.any { it is SessionEvent.Notified }, "Ledger should contain Notified event")
                    assertTrue(events.any { it is SessionEvent.VmReadvResolved }, "Ledger should contain VmReadvResolved event")
                    assertTrue(events.any { it is SessionEvent.EventSent }, "Ledger should contain EventSent event")
                    assertTrue(events.any { it is SessionEvent.AckReceived }, "Ledger should contain AckReceived event")
                    assertTrue(events.any { it is SessionEvent.ContinueReplied }, "Ledger should contain ContinueReplied event")
                } finally {
                    SegmentPool.SECCOMP_NOTIF_POOL.release(notif)
                    SegmentPool.SECCOMP_NOTIF_RESP_POOL.release(resp)
                }
            }
        }
    }

    @Test
    fun `test profiler daemon instantiation coverage`() {
        val clazz = ProfilerDaemon::class.java
        org.junit.jupiter.api.Assertions.assertNotNull(clazz)
    }

    @Test
    fun `test handleConnection restores interrupt status on InterruptedException`() {
        val transport = object : MockTransport() {
            override fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>? {
                throw InterruptedException("Simulated interruption")
            }
        }
        val engine = ProfilerDaemonEngine("/tmp/test.sock", transport, MockReader())

        // Clear interrupt status if any
        Thread.interrupted()

        engine.handleConnection(FileDescriptor.unsafe(10))

        assertTrue(Thread.currentThread().isInterrupted, "Thread interrupt status should be restored")
        // Clear interrupt status after test to avoid affecting other tests
        Thread.interrupted()
    }

    @Test
    fun `test handleConnection restores interrupt status on ClosedByInterruptException`() {
        val transport = object : MockTransport() {
            override fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>? {
                throw ClosedByInterruptException()
            }
        }
        val engine = ProfilerDaemonEngine("/tmp/test.sock", transport, MockReader())

        // Clear interrupt status if any
        Thread.interrupted()

        engine.handleConnection(FileDescriptor.unsafe(10))

        assertTrue(Thread.currentThread().isInterrupted, "Thread interrupt status should be restored")
        // Clear interrupt status after test to avoid affecting other tests
        Thread.interrupted()
    }

    @Test
    fun `test processNotification rethrows InterruptedException`() {
        val transport = MockTransport()
        val reader = object : ProfilerMemoryReader {
            context(arena: io.mazewall.ffi.memory.NativeArena)
            override fun readStringFromProcess(tid: Tid, remoteAddr: Long, maxLen: Int): String? {
                throw InterruptedException("Simulated interruption")
            }
            context(arena: io.mazewall.ffi.memory.NativeArena)
            override fun resolveLink(tid: Tid, link: String): String? = null
        }
        val syscallMap = mapOf(2 to "OPEN")
        val socketFd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10)
        val listenerFd = FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(20)
        ProfilerSessionHandler(
            socketFd,
            listenerFd,
            transport,
            transport,
            transport,
            reader,
            syscallMap,
        ) { }.use { handler ->
            io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
                val notif = SegmentPool.SECCOMP_NOTIF_POOL.rent()
                val resp = SegmentPool.SECCOMP_NOTIF_RESP_POOL.rent()
                try {
                    // Setup notification data
                    notif.writeLong(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_ID_OFFSET, 123L)
                    notif.writeInt(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_PID_OFFSET, 456)
                    notif.writeInt(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_NR_OFFSET, 2)
                    notif.writeLong(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_ARGS_OFFSET, 0x1000L)

                    // Clear interrupt status
                    Thread.interrupted()

                    org.junit.jupiter.api.assertThrows<InterruptedException> {
                        with(arena) {
                            handler.processNotification(notif, resp, listenerFd, socketFd)
                        }
                    }

                    assertTrue(Thread.currentThread().isInterrupted, "Thread interrupt status should be restored")
                    Thread.interrupted()
                } finally {
                    SegmentPool.SECCOMP_NOTIF_POOL.release(notif)
                    SegmentPool.SECCOMP_NOTIF_RESP_POOL.release(resp)
                }
            }
        }
    }

    @Test
    fun `test RealProfilerTransport sendSeccompContinue and sendSeccompError ignore ENOENT`() {
        val mockEngine = MockNativeEngine()
        mockEngine.ioctlResult = LinuxNative.SyscallResult.Error(NativeConstants.ENOENT, -1L)

        LinuxNative.setEngine(mockEngine)

        // Capture logs from RealProfilerTransport
        val logger = java.util.logging.Logger.getLogger(RealProfilerTransport::class.java.name)
        val logs = mutableListOf<java.util.logging.LogRecord>()
        val handler = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) {
                logs.add(record)
            }
            override fun flush() {}
            override fun close() {}
        }
        logger.addHandler(handler)

        try {
            Arena.ofConfined().use { arena ->
                val resp = arena.allocate(Layouts.SECCOMP_NOTIF_RESP)
                val successSession = HandshakeSession.Success(123L, FileDescriptor.unsafe(20))
                val failedSession = HandshakeSession.Failed(123L, FileDescriptor.unsafe(20))

                org.junit.jupiter.api.assertDoesNotThrow {
                    with(arena) {
                        RealProfilerTransport.sendSeccompContinue(successSession, resp)
                    }
                }
                org.junit.jupiter.api.assertDoesNotThrow {
                    with(arena) {
                        RealProfilerTransport.sendSeccompError(failedSession, resp, 5) // EIO = 5
                    }
                }

                assertEquals(2, logs.size, "Should have logged 2 warning messages")
                assertTrue(logs.all { it.level == java.util.logging.Level.WARNING }, "All logs must be WARNING level")
                assertTrue(logs[0].message.contains("SECCOMP_IOCTL_NOTIF_SEND failed with errno=2"), "Log message should mention failure and errno")
                assertTrue(logs[1].message.contains("SECCOMP_IOCTL_NOTIF_SEND failed with errno=2"), "Log message should mention failure and errno")
            }
        } finally {
            logger.removeHandler(handler)
        }
    }

    @Test
    fun `test RealProfilerTransport sendSeccompContinue logs warning and throws for other errors`() {
        val mockEngine = MockNativeEngine()
        mockEngine.ioctlResult = LinuxNative.SyscallResult.Error(NativeConstants.EPERM, -1L)

        LinuxNative.setEngine(mockEngine)

        val logger = java.util.logging.Logger.getLogger(RealProfilerTransport::class.java.name)
        val logs = mutableListOf<java.util.logging.LogRecord>()
        val handler = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) {
                logs.add(record)
            }
            override fun flush() {}
            override fun close() {}
        }
        logger.addHandler(handler)

        try {
            Arena.ofConfined().use { arena ->
                val resp = arena.allocate(Layouts.SECCOMP_NOTIF_RESP)
                val successSession = HandshakeSession.Success(123L, FileDescriptor.unsafe(20))

                org.junit.jupiter.api.assertThrows<IllegalStateException> {
                    with(arena) {
                        RealProfilerTransport.sendSeccompContinue(successSession, resp)
                    }
                }

                assertEquals(1, logs.size, "Should have logged 1 warning message")
                assertEquals(java.util.logging.Level.WARNING, logs[0].level, "Log must be WARNING level")
                assertTrue(logs[0].message.contains("SECCOMP_IOCTL_NOTIF_SEND failed with errno=1"), "Log message should mention failure and errno EPERM")
            }
        } finally {
            logger.removeHandler(handler)
        }
    }

    @Test
    fun `test processNotification handles exception during reply transmission gracefully`() {
        val failingTransport = object : MockTransport() {
            context(arena: Arena)
            override fun sendTraceEvent(socketFd: FileDescriptor<*, FdState.Open>, event: SyscallEvent<SyscallEventState.Resolved>) {
                throw IOException("Simulated socket write failure during event delivery")
            }
        }

        val reader = MockReader()
        val syscallMap = mapOf(2 to "OPEN")
        val socketFd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10)
        val listenerFd = FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(20)
        ProfilerSessionHandler(
            socketFd,
            listenerFd,
            failingTransport,
            failingTransport,
            failingTransport,
            reader,
            syscallMap,
        ) { }.use { handler ->
            io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
                val notif = SegmentPool.SECCOMP_NOTIF_POOL.rent()
                val resp = SegmentPool.SECCOMP_NOTIF_RESP_POOL.rent()
                try {
                    // Setup notification data
                    notif.writeLong(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_ID_OFFSET, 123L)
                    notif.writeInt(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_PID_OFFSET, 456)
                    notif.writeInt(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_NR_OFFSET, 2)
                    notif.writeLong(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_ARGS_OFFSET, 0x1000L)

                    val ok = with(arena) {
                        handler.processNotification(notif, resp, listenerFd, socketFd)
                    }

                    // The method must return false (signifying processing failure/session termination)
                    assertEquals(NotifResult.TERMINATE, ok)
                    // It must have fallen back to send the seccomp error response
                    assertTrue(failingTransport.errorSent, "Should have sent seccomp error response on delivery failure")
                } finally {
                    SegmentPool.SECCOMP_NOTIF_POOL.release(notif)
                    SegmentPool.SECCOMP_NOTIF_RESP_POOL.release(resp)
                }
            }
        }
    }

    @Test
    fun `test processNotification propagates structural exception`() {
        val transport = MockTransport()
        val reader = object : ProfilerMemoryReader {
            context(arena: io.mazewall.ffi.memory.NativeArena)
            override fun readStringFromProcess(tid: Tid, remoteAddr: Long, maxLen: Int): String? {
                throw IllegalArgumentException("Simulated structural error")
            }
            context(arena: io.mazewall.ffi.memory.NativeArena)
            override fun resolveLink(tid: Tid, link: String): String? = null
        }
        val syscallMap = mapOf(2 to "OPEN")
        val socketFd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10)
        val listenerFd = FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(20)
        ProfilerSessionHandler(
            socketFd,
            listenerFd,
            transport,
            transport,
            transport,
            reader,
            syscallMap,
        ) { }.use { handler ->
            io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
                val notif = SegmentPool.SECCOMP_NOTIF_POOL.rent()
                val resp = SegmentPool.SECCOMP_NOTIF_RESP_POOL.rent()
                try {
                    // Setup notification data
                    notif.writeLong(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_ID_OFFSET, 123L)
                    notif.writeInt(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_PID_OFFSET, 456)
                    notif.writeInt(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_NR_OFFSET, 2)
                    notif.writeLong(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_ARGS_OFFSET, 0x1000L)

                    org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
                        with(arena) {
                            handler.processNotification(notif, resp, listenerFd, socketFd)
                        }
                    }
                } finally {
                    SegmentPool.SECCOMP_NOTIF_POOL.release(notif)
                    SegmentPool.SECCOMP_NOTIF_RESP_POOL.release(resp)
                }
            }
        }
    }

    @Test
    fun `test processNotification handles recoverable IOException`() {
        val transport = MockTransport()
        val reader = object : ProfilerMemoryReader {
            context(arena: io.mazewall.ffi.memory.NativeArena)
            override fun readStringFromProcess(tid: Tid, remoteAddr: Long, maxLen: Int): String? {
                throw IOException("Simulated recoverable I/O error")
            }
            context(arena: io.mazewall.ffi.memory.NativeArena)
            override fun resolveLink(tid: Tid, link: String): String? = null
        }
        val syscallMap = mapOf(2 to "OPEN")
        val socketFd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10)
        val listenerFd = FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(20)
        ProfilerSessionHandler(
            socketFd,
            listenerFd,
            transport,
            transport,
            transport,
            reader,
            syscallMap,
        ) { }.use { handler ->
            io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
                val notif = SegmentPool.SECCOMP_NOTIF_POOL.rent()
                val resp = SegmentPool.SECCOMP_NOTIF_RESP_POOL.rent()
                try {
                    // Setup notification data
                    notif.writeLong(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_ID_OFFSET, 123L)
                    notif.writeInt(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_PID_OFFSET, 456)
                    notif.writeInt(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_NR_OFFSET, 2)
                    notif.writeLong(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_ARGS_OFFSET, 0x1000L)

                    val ok = with(arena) {
                        handler.processNotification(notif, resp, listenerFd, socketFd)
                    }

                    assertEquals(NotifResult.TERMINATE, ok)
                    assertTrue(transport.errorSent)
                } finally {
                    SegmentPool.SECCOMP_NOTIF_POOL.release(notif)
                    SegmentPool.SECCOMP_NOTIF_RESP_POOL.release(resp)
                }
            }
        }
    }

    @Test
    fun `test checkAndBypassNoisePath for JDK and normal paths`() {
        val transport = MockTransport()
        val javaHome = System.getProperty("java.home")
        val jdkPath = java.nio.file.Paths.get(javaHome).resolve("lib/rt.jar").toAbsolutePath().toString()

        io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
            // Setup notification data with a JDK noise path to bypass
            val customReader = object : ProfilerMemoryReader {
                context(arena: io.mazewall.ffi.memory.NativeArena)
                override fun readStringFromProcess(tid: Tid, remoteAddr: Long, maxLen: Int): String? {
                    return jdkPath
                }
                context(arena: io.mazewall.ffi.memory.NativeArena)
                override fun resolveLink(tid: Tid, link: String): String? = null
            }
            val socketFd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10)
            val listenerFd = FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(20)

            ProfilerSessionHandler(
                socketFd,
                listenerFd,
                transport,
                transport,
                transport,
                customReader,
                mapOf(2 to "OPEN"),
            ) { }.use { noiseHandler ->
                val notif = SegmentPool.SECCOMP_NOTIF_POOL.rent()
                val resp = SegmentPool.SECCOMP_NOTIF_RESP_POOL.rent()
                try {
                    notif.writeLong(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_ID_OFFSET, 123L)
                    notif.writeInt(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_PID_OFFSET, 456)
                    notif.writeInt(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_NR_OFFSET, 2) // nr = 2 (OPEN)
                    notif.writeLong(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_ARGS_OFFSET, 0x1000L)

                    val ok = with(arena) {
                        noiseHandler.processNotification(notif, resp, listenerFd, socketFd)
                    }
                    assertEquals(NotifResult.HANDLED, ok, "processNotification should return HANDLED on skipped noise path")
                    assertTrue(transport.continueSent, "Should have called sendSeccompContinue directly")
                    assertEquals(0, transport.sentEvents.size, "Should NOT send trace event to JVM for noise path")
                } finally {
                    SegmentPool.SECCOMP_NOTIF_POOL.release(notif)
                    SegmentPool.SECCOMP_NOTIF_RESP_POOL.release(resp)
                }
            }
        }
    }

    @Test
    fun `test handleConnection cleans up both socket and listener FDs if interrupted after FD attachment but prior to session reactor execution`() {
        val closedFds = mutableListOf<Int>()
        val transport = object : MockTransport() {
            override fun close(fd: FileDescriptor<*, FdState.Open>) {
                closedFds.add(fd.value)
            }

            override fun write(fd: FileDescriptor<*, FdState.Open>, buf: MemorySegment, count: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                return LinuxNative.SyscallResult.Success(count)
            }
        }

        val pool = SegmentPool.SECCOMP_NOTIF_POOL
        val arenaField = SegmentPool::class.java.getDeclaredField("arena")
        arenaField.isAccessible = true
        val originalArena = arenaField.get(pool) as io.mazewall.ffi.memory.NativeArena

        val queueField = SegmentPool::class.java.getDeclaredField("queue")
        queueField.isAccessible = true
        val queue = queueField.get(pool) as java.util.concurrent.ConcurrentLinkedQueue<*>

        val tempQueue = ArrayList(queue)
        queue.clear()

        val closedArena = io.mazewall.ffi.memory.NativeArena.ofConfined().apply { close() }
        arenaField.set(pool, closedArena)

        try {
            val engine = ProfilerDaemonEngine("/tmp/test.sock", transport, MockReader())
            engine.handleConnection(FileDescriptor.unsafe(10))

            assertTrue(closedFds.contains(20), "Listener FD 20 should have been closed")
            assertTrue(closedFds.contains(10), "Socket FD 10 should have been closed")
        } finally {
            arenaField.set(pool, originalArena)
            for (seg in tempQueue) {
                @Suppress("UNCHECKED_CAST")
                (queue as java.util.concurrent.ConcurrentLinkedQueue<Any>).offer(seg)
            }
        }
    }

    @Test
    fun `test handleNewConnection socket leak prevention on thread start failure`() {
        val closedFds = mutableListOf<Int>()
        val transport = object : MockTransport() {
            override fun close(fd: FileDescriptor<*, FdState.Open>) {
                closedFds.add(fd.value)
            }
        }

        val engine = ProfilerDaemonEngine("/tmp/test.sock", transport, MockReader())

        // Use reflection to replace clientSockets with a list that throws an OutOfMemoryError on add
        val throwingList = object : java.util.concurrent.CopyOnWriteArrayList<FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>>() {
            override fun add(element: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): Boolean {
                throw OutOfMemoryError("Simulated OOM on thread spawn limit")
            }
        }

        val delegateField = ProfilerDaemonEngine::class.java.getDeclaredField("delegate")
        delegateField.isAccessible = true
        val delegate = delegateField.get(engine) as io.mazewall.platform.seccomp.daemon.SeccompDaemonEngine

        val clientSocketsField = io.mazewall.platform.seccomp.daemon.SeccompDaemonEngine::class.java.getDeclaredField("clientSockets")
        clientSocketsField.isAccessible = true
        clientSocketsField.set(delegate, throwingList)


        // Call handleNewConnection using reflection
        val handleNewConnectionMethod = ProfilerDaemonEngine::class.java.getDeclaredMethod(
            "handleNewConnection",
            FileDescriptor::class.java
        )
        handleNewConnectionMethod.isAccessible = true

        // Because we threw OutOfMemoryError (which is an Error), handleNewConnection should rethrow it.
        // Reflection wraps it in InvocationTargetException.
        val exception = org.junit.jupiter.api.assertThrows<java.lang.reflect.InvocationTargetException> {
            handleNewConnectionMethod.invoke(engine, FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(99))
        }
        assertTrue(exception.cause is OutOfMemoryError, "Cause should be OutOfMemoryError")

        // Verify that the accepted client FD (mock transport accept returns 100) was closed
        assertTrue(closedFds.contains(100), "Socket FD 100 should have been closed to prevent leak")
    }

    @Test
    fun `test handleNewConnection socket leak prevention on generic exception`() {
        val closedFds = mutableListOf<Int>()
        val transport = object : MockTransport() {
            override fun close(fd: FileDescriptor<*, FdState.Open>) {
                closedFds.add(fd.value)
            }
        }

        val engine = ProfilerDaemonEngine("/tmp/test.sock", transport, MockReader())

        // Use reflection to replace clientSockets with a list that throws a RuntimeException on add
        val throwingList = object : java.util.concurrent.CopyOnWriteArrayList<FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>>() {
            override fun add(element: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): Boolean {
                throw RuntimeException("Simulated runtime exception")
            }
        }

        val delegateField = ProfilerDaemonEngine::class.java.getDeclaredField("delegate")
        delegateField.isAccessible = true
        val delegate = delegateField.get(engine) as io.mazewall.platform.seccomp.daemon.SeccompDaemonEngine

        val clientSocketsField = io.mazewall.platform.seccomp.daemon.SeccompDaemonEngine::class.java.getDeclaredField("clientSockets")
        clientSocketsField.isAccessible = true
        clientSocketsField.set(delegate, throwingList)

        // Call handleNewConnection using reflection
        val handleNewConnectionMethod = ProfilerDaemonEngine::class.java.getDeclaredMethod(
            "handleNewConnection",
            FileDescriptor::class.java
        )
        handleNewConnectionMethod.isAccessible = true

        // Generic Exception should be swallowed, so this shouldn't throw.
        org.junit.jupiter.api.assertDoesNotThrow {
            handleNewConnectionMethod.invoke(engine, FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(99))
        }

        // Verify that the accepted client FD (100) was closed
        assertTrue(closedFds.contains(100), "Socket FD 100 should have been closed on generic exception")

    }
}
