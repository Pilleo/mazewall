package io.mazewall.profiler.internal

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeMemory
import io.mazewall.NativeTransaction
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.writeByte
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class NativeSocketInputStreamTest {
    @Test
    fun `read should retry on EINTR`() {
        var attempts = 0
        val mock = MockNativeEngine(
            memory = object : MockNativeMemory() {
                context(_: NativeTransaction)
                override fun read(fd: FileDescriptor<*, FdState.Open>, buf: ManagedSegment, count: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    attempts++
                    return if (attempts <= 2) {
                        // Simulate EINTR (errno 4) for the first two attempts
                        LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(4, -1L)
                    } else {
                        // Return a successful byte (0x42) on the third attempt
                        buf.writeByte(0L, 0x42.toByte())
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
    fun `bulk read should retry on EINTR`() {
        var attempts = 0
        val mock = MockNativeEngine(
            memory = object : MockNativeMemory() {
                context(_: NativeTransaction)
                override fun read(fd: FileDescriptor<*, FdState.Open>, buf: ManagedSegment, count: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    attempts++
                    return if (attempts <= 2) {
                        // Simulate EINTR (errno 4) for the first two attempts
                        LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(4, -1L)
                    } else {
                        // Return a successful byte (0x42) on the third attempt
                        buf.writeByte(0L, 0x42.toByte())
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
}
