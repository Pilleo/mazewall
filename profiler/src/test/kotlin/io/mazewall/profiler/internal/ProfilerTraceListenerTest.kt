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
}
