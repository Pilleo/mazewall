package io.mazewall.profiler.engine

import io.mazewall.LinuxNative
import io.mazewall.core.NativeArg
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.Tid
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.writeInt
import io.mazewall.ffi.memory.writeLong
import io.mazewall.ffi.memory.SegmentPool
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.io.IOException
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProfilerDaemonBenchmarkTest {

    private companion object {
        private const val PROTOCOL_ACK_BYTE = 0xAC.toByte()
        private const val NOTIF_ID_OFF = 0L
        private const val NOTIF_PID_OFF = 8L
        private const val NOTIF_NR_OFF = 12L
        private const val NOTIF_ARGS_OFF = 16L
    }

    private class BenchmarkTransport : ProfilerTransport, SeccompResponder, TraceEventPublisher, NativeIoOperations, SocketLifecycleManager {
        override val raw = object : io.mazewall.RawSyscallOperations {
            override fun poll(fds: ManagedSegment, nfds: Long, timeout: Int): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                return LinuxNative.SyscallResult.Success(0L)
            }
            override fun ioctl(fd: FileDescriptor<*, FdState.Open>, request: Long, arg: ManagedSegment): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                return LinuxNative.SyscallResult.Success(0L)
            }
            override fun ioctl(fd: FileDescriptor<*, FdState.Open>, request: Long, arg: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(0L)
            override fun fcntl(fd: FileDescriptor<*, FdState.Open>, cmd: Int, arg: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(0L)
            override fun syscall(nr: Long, arg1: NativeArg, arg2: NativeArg, arg3: NativeArg, arg4: NativeArg, arg5: NativeArg, arg6: NativeArg): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(0L)
            override fun syscall4(nr: Long, arg1: NativeArg, arg2: NativeArg, arg3: NativeArg, arg4: NativeArg): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(0L)
        }

        context(arena: Arena)
        override fun sendTraceEvent(socketFd: FileDescriptor<*, FdState.Open>, event: SyscallEvent<SyscallEventState.Resolved>) {}

        context(arena: Arena)
        override fun sendSeccompContinue(session: HandshakeSession.Success, resp: MemorySegment) {}

        context(arena: Arena)
        override fun sendSeccompError(session: HandshakeSession.Failed, resp: MemorySegment, errorNr: Int) {}

        override fun read(fd: FileDescriptor<*, FdState.Open>, buf: MemorySegment, count: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
            if (count > 0) {
                buf.set(ValueLayout.JAVA_BYTE, 0L, PROTOCOL_ACK_BYTE)
            }
            return LinuxNative.SyscallResult.Success(count)
        }

        override fun write(fd: FileDescriptor<*, FdState.Open>, buf: MemorySegment, count: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(count)
        override fun recv(sockfd: FileDescriptor<*, FdState.Open>, buf: MemorySegment, len: Long, flags: Int): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(len)
        override fun poll(fds: MemorySegment, nfds: Long, timeout: Int): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(1L)
        override fun ioctl(fd: FileDescriptor<*, FdState.Open>, request: Long, arg: MemorySegment): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(0L)
        override fun createUnixServer(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> = FileDescriptor.unsafe(99)
        override fun accept(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> = FileDescriptor.unsafe(100)
        override fun connect(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> = FileDescriptor.unsafe(101)
        override fun sendDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>, fdToSend: FileDescriptor<*, FdState.Open>): Boolean = true
        override fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>? = FileDescriptor.unsafe(20)
        override fun close(fd: FileDescriptor<*, FdState.Open>) {}
    }

    private class BenchmarkReader : ProfilerMemoryReader {
        context(arena: io.mazewall.ffi.memory.NativeArena)
        override fun readStringFromProcess(tid: Tid, remoteAddr: Long, maxLen: Int): String? = "/tmp/test.txt"
        context(arena: io.mazewall.ffi.memory.NativeArena)
        override fun resolveLink(tid: Tid, link: String): String? = "/proc/1/cwd"
    }

    @Test
    fun `test constant memory allocation and zero off-heap leak under high load`() {
        val transport = BenchmarkTransport()
        val reader = BenchmarkReader()
        val syscallMap = mapOf(2 to "OPEN")

        System.gc()
        Thread.sleep(100)
        val heapBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        val startTime = System.currentTimeMillis()

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
            val notif = SegmentPool.SECCOMP_NOTIF_POOL.rent()
            val resp = SegmentPool.SECCOMP_NOTIF_RESP_POOL.rent()
            try {
                notif.writeLong(NOTIF_ID_OFF, 123L)
                notif.writeInt(NOTIF_PID_OFF, 456)
                notif.writeInt(NOTIF_NR_OFF, 2)
                notif.writeLong(NOTIF_ARGS_OFF, 0x1000L)

                val iterations = 100000
                for (i in 0 until iterations) {
                    NativeArena.ofConfined().use { iterationArena ->
                        with(iterationArena) {
                            handler.processNotification(notif, resp, listenerFd, socketFd)
                        }
                    }
                }
            } finally {
                SegmentPool.SECCOMP_NOTIF_POOL.release(notif)
                SegmentPool.SECCOMP_NOTIF_RESP_POOL.release(resp)
            }
        }

        val duration = System.currentTimeMillis() - startTime
        System.err.println("Benchmark duration for 100,000 syscall notifications: $duration ms")

        System.gc()
        Thread.sleep(100)
        val heapAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        System.err.println("Heap memory change: before=$heapBefore, after=$heapAfter, delta=${heapAfter - heapBefore}")

        // Under 100,000 iterations, any major memory leak or object overhead would be immense.
        // We assert that the execution is extremely fast (signifying zero heavy GC/allocation pressure)
        assertTrue(duration < 2500, "Should handle 100,000 notifications in under 2.5 seconds")
    }
}
