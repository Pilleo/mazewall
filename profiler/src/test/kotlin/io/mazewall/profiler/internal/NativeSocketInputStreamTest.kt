package io.mazewall.profiler.internal

import io.mazewall.LinuxNative
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
        val mockRead: (Int, MemorySegment, Long) -> LinuxNative.SyscallResult = { _, buf, _ ->
            attempts++
            if (attempts <= 2) {
                // Simulate EINTR (errno 4) for the first two attempts
                LinuxNative.SyscallResult(-1, 4)
            } else {
                // Return a successful byte (0x42) on the third attempt
                buf.set(ValueLayout.JAVA_BYTE, 0L, 0x42.toByte())
                LinuxNative.SyscallResult(1, 0)
            }
        }

        Arena.ofConfined().use { arena ->
            val stream = NativeSocketInputStream(1, arena, mockRead) { }
            val result = stream.read()
            // This will FAIL with the buggy implementation because it returns -1 on the first attempt
            assertEquals(0x42, result)
            assertEquals(3, attempts)
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `bulk read should retry on EINTR`() {
        var attempts = 0
        val mockRead: (Int, MemorySegment, Long) -> LinuxNative.SyscallResult = { _, buf, _ ->
            attempts++
            if (attempts <= 2) {
                // Simulate EINTR (errno 4) for the first two attempts
                LinuxNative.SyscallResult(-1, 4)
            } else {
                // Return a successful byte (0x42) on the third attempt
                buf.set(ValueLayout.JAVA_BYTE, 0L, 0x42.toByte())
                LinuxNative.SyscallResult(1, 0)
            }
        }

        Arena.ofConfined().use { arena ->
            val stream = NativeSocketInputStream(1, arena, mockRead) { }
            val buffer = ByteArray(1)
            val result = stream.read(buffer)
            // This will FAIL with the buggy implementation because it returns -1 on the first attempt
            assertEquals(1, result)
            assertEquals(0x42.toByte(), buffer[0])
            assertEquals(3, attempts)
        }
    }
}
