package io.mazewall.profiler.internal

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeMemory
import io.mazewall.NativeTransaction
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.TimeUnit

class NativeSocketInputStreamTest {
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `read should retry on EINTR`() {
        var attempts = 0
        val mock = MockNativeEngine(
            memory = object : MockNativeMemory() {
                context(_: NativeTransaction)
                override fun read(fd: FileDescriptor<*>, buf: MemorySegment, count: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    attempts++
                    return if (attempts <= 2) {
                        // Simulate EINTR (errno 4) for the first two attempts
                        LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(4, -1L)
                    } else {
                        // Return a successful byte (0x42) on the third attempt
                        buf.set(ValueLayout.JAVA_BYTE, 0L, 0x42.toByte())
                        LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(1L)
                    }
                }
            }
        )

        LinuxNative.setEngine(mock)
        try {
            Arena.ofConfined().use { arena ->
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
                context(_: NativeTransaction)
                override fun read(fd: FileDescriptor<*>, buf: MemorySegment, count: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    attempts++
                    return if (attempts <= 2) {
                        // Simulate EINTR (errno 4) for the first two attempts
                        LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(4, -1L)
                    } else {
                        // Return a successful byte (0x42) on the third attempt
                        buf.set(ValueLayout.JAVA_BYTE, 0L, 0x42.toByte())
                        LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(1L)
                    }
                }
            }
        )

        LinuxNative.setEngine(mock)
        try {
            Arena.ofConfined().use { arena ->
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
}
