package io.mazewall.profiler.internal

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeMemory
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

class NativeSocketInputStreamTest {
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `read should retry on EINTR`() {
        var attempts = 0
        val mock = MockNativeEngine(
            memory = object : MockNativeMemory() {
                override fun read(fd: FileDescriptor<*, FdState.Open>, buf: ManagedSegment, count: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    attempts++
                    return if (attempts <= 2) {
                        // Simulate EINTR (errno 4) for the first two attempts
                        LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(4, -1L)
                    } else {
                        // Return a successful byte (0x42) on the third attempt
                        ManagedSegment.copy(byteArrayOf(0x42.toByte()), 0, buf, 0L, 1)
                        LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(1L)
                    }
                }
            }
        )

        LinuxNative.setEngine(mock)
        try {
            NativeArena.ofConfined().use { arena ->
                val stream = NativeSocketInputStream(FileDescriptor.unsafe<FileDescriptorRole.Generic>(1), arena)
                val result = stream.read()
                assertEquals(0x42, result)
                assertEquals(3, attempts)
            }
        } finally {
            LinuxNative.resetToDefault()
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `bulk read should retry on EINTR`() {
        var attempts = 0
        val mock = MockNativeEngine(
            memory = object : MockNativeMemory() {
                override fun read(fd: FileDescriptor<*, FdState.Open>, buf: ManagedSegment, count: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    attempts++
                    return if (attempts <= 2) {
                        // Simulate EINTR (errno 4) for the first two attempts
                        LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(4, -1L)
                    } else {
                        // Return a successful byte (0x42) on the third attempt
                        ManagedSegment.copy(byteArrayOf(0x42.toByte()), 0, buf, 0L, 1)
                        LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(1L)
                    }
                }
            }
        )

        LinuxNative.setEngine(mock)
        try {
            NativeArena.ofConfined().use { arena ->
                val stream = NativeSocketInputStream(FileDescriptor.unsafe<FileDescriptorRole.Generic>(1), arena)
                val buffer = ByteArray(1)
                val result = stream.read(buffer)
                assertEquals(1, result)
                assertEquals(0x42.toByte(), buffer[0])
                assertEquals(3, attempts)
            }
        } finally {
            LinuxNative.resetToDefault()
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `read should throw InterruptedIOException and restore interrupt status when thread is interrupted`() {
        val mock = MockNativeEngine(
            memory = object : MockNativeMemory() {
                override fun read(fd: FileDescriptor<*, FdState.Open>, buf: ManagedSegment, count: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    // Should not even be called if checked before read, or can be called once
                    return LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(4, -1L)
                }
            }
        )

        LinuxNative.setEngine(mock)
        try {
            NativeArena.ofConfined().use { arena ->
                val stream = NativeSocketInputStream(FileDescriptor.unsafe<FileDescriptorRole.Generic>(1), arena)
                Thread.currentThread().interrupt()
                try {
                    stream.read()
                    fail("Expected InterruptedIOException to be thrown")
                } catch (e: InterruptedIOException) {
                    // Expected!
                    assertTrue(Thread.currentThread().isInterrupted, "Interrupted status should be restored/preserved")
                } finally {
                    // Clear interrupted status so other tests/framework works fine
                    Thread.interrupted()
                }
            }
        } finally {
            LinuxNative.resetToDefault()
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `readWithRetry should throw InterruptedIOException and restore interrupt status when thread is interrupted`() {
        val mock = MockNativeEngine(
            memory = object : MockNativeMemory() {
                override fun read(fd: FileDescriptor<*, FdState.Open>, buf: ManagedSegment, count: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    return LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(4, -1L)
                }
            }
        )

        LinuxNative.setEngine(mock)
        try {
            NativeArena.ofConfined().use { arena ->
                val stream = NativeSocketInputStream(FileDescriptor.unsafe<FileDescriptorRole.Generic>(1), arena)
                val buffer = ByteArray(1)
                Thread.currentThread().interrupt()
                try {
                    stream.read(buffer)
                    fail("Expected InterruptedIOException to be thrown")
                } catch (e: InterruptedIOException) {
                    // Expected!
                    assertTrue(Thread.currentThread().isInterrupted, "Interrupted status should be restored/preserved")
                } finally {
                    Thread.interrupted()
                }
            }
        } finally {
            LinuxNative.resetToDefault()
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `read should trigger progressive backoff on consecutive EINTRs and throw InterruptedIOException if interrupted during sleep`() {
        var callCount = 0
        val mock = MockNativeEngine(
            memory = object : MockNativeMemory() {
                override fun read(fd: FileDescriptor<*, FdState.Open>, buf: ManagedSegment, count: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    callCount++
                    if (callCount == 4) {
                        // On the 4th call, we will interrupt the thread to trigger InterruptedException inside backoff sleep
                        Thread.currentThread().interrupt()
                    }
                    return LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(4, -1L)
                }
            }
        )

        LinuxNative.setEngine(mock)
        try {
            NativeArena.ofConfined().use { arena ->
                val stream = NativeSocketInputStream(FileDescriptor.unsafe<FileDescriptorRole.Generic>(1), arena)
                try {
                    stream.read()
                    fail("Expected InterruptedIOException from sleep interruption")
                } catch (e: InterruptedIOException) {
                    // Expected!
                    assertTrue(Thread.currentThread().isInterrupted, "Interrupted status should be restored/preserved")
                } finally {
                    Thread.interrupted()
                }
            }
        } finally {
            LinuxNative.resetToDefault()
        }
    }
}
