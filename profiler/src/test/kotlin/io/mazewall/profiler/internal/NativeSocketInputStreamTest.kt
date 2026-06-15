package io.mazewall.profiler.internal

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeMemory
import io.mazewall.NativeTransaction
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
                override fun read(fd: LinuxNative.FileDescriptor, buf: MemorySegment, count: Long): LinuxNative.SyscallResult {
                    attempts++
                    return if (attempts <= 2) {
                        // Simulate EINTR (errno 4) for the first two attempts
                        LinuxNative.SyscallResult.Error(4, -1)
                    } else {
                        // Return a successful byte (0x42) on the third attempt
                        buf.set(ValueLayout.JAVA_BYTE, 0L, 0x42.toByte())
                        LinuxNative.SyscallResult.Success(1)
                    }
                }
            }
        )

        LinuxNative.setEngine(mock)
        try {
            Arena.ofConfined().use { arena ->
                val stream = NativeSocketInputStream(LinuxNative.FileDescriptor(1), arena)
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
                override fun read(fd: LinuxNative.FileDescriptor, buf: MemorySegment, count: Long): LinuxNative.SyscallResult {
                    attempts++
                    return if (attempts <= 2) {
                        // Simulate EINTR (errno 4) for the first two attempts
                        LinuxNative.SyscallResult.Error(4, -1)
                    } else {
                        // Return a successful byte (0x42) on the third attempt
                        buf.set(ValueLayout.JAVA_BYTE, 0L, 0x42.toByte())
                        LinuxNative.SyscallResult.Success(1)
                    }
                }
            }
        )

        LinuxNative.setEngine(mock)
        try {
            Arena.ofConfined().use { arena ->
                val stream = NativeSocketInputStream(LinuxNative.FileDescriptor(1), arena)
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
