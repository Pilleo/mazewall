package io.mazewall.profiler.internal

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeFileSystem
import io.mazewall.MockNativeMemory
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.*
import io.mazewall.profiler.engine.TraceEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProfilerTraceListenerTest {

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `close should close the socket exactly once on graceful drain`() {
        val closeCount = AtomicInteger(0)
        val readLatch = CountDownLatch(1)

        val mock = MockNativeEngine(
            fileSystem = object : MockNativeFileSystem() {
                override fun close(fd: FileDescriptor<*, FdState.Open>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    closeCount.incrementAndGet()
                    return LinuxNative.SyscallResult.Success(0L)
                }
            },
            memory = object : MockNativeMemory() {
                override fun read(fd: FileDescriptor<*, FdState.Open>, buf: ManagedSegment, count: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    readLatch.await(5, TimeUnit.SECONDS)
                    return LinuxNative.SyscallResult.Success(0L)
                }

                override fun write(fd: FileDescriptor<*, FdState.Open>, buf: ManagedSegment, count: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    readLatch.countDown()
                    return LinuxNative.SyscallResult.Success(count)
                }
            }
        )

        LinuxNative.setEngine(mock)
        try {
            val socketFd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(100)
            val listener = ProfilerTraceListener(
                socketFd = socketFd,
                accumulatedLogs = mutableListOf(),
                stackTracesMap = null,
                pathCache = mutableMapOf()
            )

            val readyLatch = CountDownLatch(1)
            listener.start(readyLatch)

            // Close the listener, which unblocks the read latch and drains
            listener.close()

            // Verify close was called exactly once
            assertEquals(1, closeCount.get())
        } finally {
            LinuxNative.resetToDefault()
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `worker thread termination closes socket exactly once and prevents double close`() {
        val closeCount = AtomicInteger(0)
        val mock = MockNativeEngine(
            fileSystem = object : MockNativeFileSystem() {
                override fun close(fd: FileDescriptor<*, FdState.Open>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    closeCount.incrementAndGet()
                    return LinuxNative.SyscallResult.Success(0L)
                }
            },
            memory = object : MockNativeMemory() {
                override fun read(fd: FileDescriptor<*, FdState.Open>, buf: ManagedSegment, count: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    // Simulate EOF immediately
                    return LinuxNative.SyscallResult.Success(0L)
                }
            }
        )

        LinuxNative.setEngine(mock)
        try {
            val socketFd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(101)
            val listener = ProfilerTraceListener(
                socketFd = socketFd,
                accumulatedLogs = mutableListOf(),
                stackTracesMap = null,
                pathCache = mutableMapOf()
            )

            val readyLatch = CountDownLatch(1)
            listener.start(readyLatch)

            readyLatch.await(2, TimeUnit.SECONDS)
            Thread.sleep(100) // allow worker thread finally block to execute

            // Verify worker thread has closed it exactly once
            assertEquals(1, closeCount.get())

            // Calling close on listener now should be a no-op and not close it again
            listener.close()

            // Verify total close calls remains 1
            assertEquals(1, closeCount.get())
        } finally {
            LinuxNative.resetToDefault()
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `test that trace events are successfully streamed into the channel and processed asynchronously`() {
        val accumulatedLogs = mutableListOf<TraceEvent>()
        val stackTracesMap = mutableMapOf<TraceEvent, MutableList<Array<StackTraceElement>>>()

        val mock = MockNativeEngine(
            memory = object : MockNativeMemory() {
                private var callCount = 0
                override fun read(fd: FileDescriptor<*, FdState.Open>, buf: ManagedSegment, count: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    if (callCount == 0) {
                        buf.writeByte(0L, 0xAC.toByte())
                        callCount++
                        return LinuxNative.SyscallResult.Success(1L)
                    } else if (callCount == 1) {
                        var offset = 0L

                        buf.writeIntUnaligned(offset, java.lang.Integer.reverseBytes(42))
                        offset += 4

                        buf.writeIntUnaligned(offset, java.lang.Integer.reverseBytes(4))
                        offset += 4

                        buf.writeByte(offset, 'O'.code.toByte())
                        buf.writeByte(offset + 1, 'P'.code.toByte())
                        buf.writeByte(offset + 2, 'E'.code.toByte())
                        buf.writeByte(offset + 3, 'N'.code.toByte())
                        offset += 4

                        buf.writeIntUnaligned(offset, java.lang.Integer.reverseBytes(0))
                        offset += 4

                        buf.writeIntUnaligned(offset, java.lang.Integer.reverseBytes(1))
                        offset += 4

                        buf.writeIntUnaligned(offset, java.lang.Integer.reverseBytes(5))
                        offset += 4

                        buf.writeByte(offset, 'h'.code.toByte())
                        buf.writeByte(offset + 1, 'e'.code.toByte())
                        buf.writeByte(offset + 2, 'l'.code.toByte())
                        buf.writeByte(offset + 3, 'l'.code.toByte())
                        buf.writeByte(offset + 4, 'o'.code.toByte())
                        offset += 5

                        callCount++
                        return LinuxNative.SyscallResult.Success(offset)
                    } else {
                        return LinuxNative.SyscallResult.Success(0L)
                    }
                }
            }
        )

        LinuxNative.setEngine(mock)
        try {
            val socketFd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(102)
            val listener = ProfilerTraceListener(
                socketFd = socketFd,
                accumulatedLogs = accumulatedLogs,
                stackTracesMap = stackTracesMap,
                pathCache = mutableMapOf()
            )

            val readyLatch = CountDownLatch(1)
            listener.start(readyLatch)
            readyLatch.await(2, TimeUnit.SECONDS)

            // Wait for the asynchronous collector thread to process the event
            var elapsed = 0
            while (accumulatedLogs.size < 1 && elapsed < 2000) {
                Thread.sleep(10)
                elapsed += 10
            }

            listener.close()

            assertEquals(1, accumulatedLogs.size)
            assertEquals("OPEN", accumulatedLogs[0].syscallName)
            assertEquals(listOf("hello"), accumulatedLogs[0].paths)
        } finally {
            LinuxNative.resetToDefault()
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `close should be safe and idempotent when called concurrently`() {
        val closeCount = AtomicInteger(0)
        val mock = MockNativeEngine(
            fileSystem = object : MockNativeFileSystem() {
                override fun close(fd: FileDescriptor<*, FdState.Open>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    closeCount.incrementAndGet()
                    return LinuxNative.SyscallResult.Success(0L)
                }
            }
        )

        LinuxNative.setEngine(mock)
        try {
            val socketFd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(103)
            val listener = ProfilerTraceListener(
                socketFd = socketFd,
                accumulatedLogs = mutableListOf(),
                stackTracesMap = null,
                pathCache = mutableMapOf()
            )

            // Concurrently invoke close() multiple times to ensure idempotency is strictly enforced
            val threads = List(10) {
                Thread {
                    listener.close()
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }

            // Double close on the listener directly should also be safe
            listener.close()

            assertEquals(1, closeCount.get())
        } finally {
            LinuxNative.resetToDefault()
        }
    }

    @Test
    fun `idempotency guard prevents double close even under multiple sequential and asynchronous close attempts`() {
        val closeCount = AtomicInteger(0)
        val mock = MockNativeEngine(
            fileSystem = object : MockNativeFileSystem() {
                override fun close(fd: FileDescriptor<*, FdState.Open>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    closeCount.incrementAndGet()
                    return LinuxNative.SyscallResult.Success(0L)
                }
            }
        )

        LinuxNative.setEngine(mock)
        try {
            val socketFd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(104)
            val listener = ProfilerTraceListener(
                socketFd = socketFd,
                accumulatedLogs = mutableListOf(),
                stackTracesMap = null,
                pathCache = mutableMapOf()
            )

            // Trigger close across all possible sequences:
            // 1. Explicit listener.close()
            listener.close()
            // 2. Sequential call to close()
            listener.close()
            // 3. Sequential call to passThrough() which also closes the socket
            listener.passThrough()

            // Verify the native file system close was called exactly once
            assertEquals(1, closeCount.get())
        } finally {
            LinuxNative.resetToDefault()
        }
    }
}
