package io.mazewall.profiler.internal

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeMemory
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.TestFileDescriptors
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

class NativeSocketInputStreamTest {
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `read should retry on EINTR`() {
        var attempts = 0
        val mock = MockNativeEngine(
            memory = object : MockNativeMemory() {
                override fun read(
                    fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    buf: ManagedSegment,
                    count: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    attempts++
                    return if (attempts <= 2) {
                        LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(4, -1L)
                    } else {
                        ManagedSegment.copy(byteArrayOf(0x42.toByte()), 0, buf, 0L, 1)
                        LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(1L)
                    }
                }
            },
        )

        run {
            NativeArena.ofConfined().use { arena ->
                val stream = NativeSocketInputStream(
                    TestFileDescriptors
                    .replace<FileDescriptorRole.Generic>(1),
                        arena,
                    mock.memory,
                )
                val result = stream.read()
                assertEquals(0x42, result)
                assertEquals(3, attempts)
            }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `bulk read should retry on EINTR`() {
        var attempts = 0
        val mock = MockNativeEngine(
            memory = object : MockNativeMemory() {
                override fun read(
                    fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    buf: ManagedSegment,
                    count: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    attempts++
                    return if (attempts <= 2) {
                        LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(4, -1L)
                    } else {
                        ManagedSegment.copy(byteArrayOf(0x42.toByte()), 0, buf, 0L, 1)
                        LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(1L)
                    }
                }
            },
        )

        run {
            NativeArena.ofConfined().use { arena ->
                val stream = NativeSocketInputStream(
                    TestFileDescriptors
                    .replace<FileDescriptorRole.Generic>(1),
                        arena,
                    mock.memory,
                )
                val buffer = ByteArray(1)
                val result = stream.read(buffer)
                assertEquals(1, result)
                assertEquals(0x42.toByte(), buffer[0])
                assertEquals(3, attempts)
            }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `read should throw InterruptedIOException and restore interrupt status when thread is interrupted`() {
        val mock = MockNativeEngine(
            memory = object : MockNativeMemory() {
                override fun read(
                    fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    buf: ManagedSegment,
                    count: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    return LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(4, -1L)
                }
            },
        )

        run {
            NativeArena.ofConfined().use { arena ->
                val stream = NativeSocketInputStream(
                    TestFileDescriptors
                    .replace<FileDescriptorRole.Generic>(1),
                        arena,
                    mock.memory,
                )
                Thread.currentThread().interrupt()
                try {
                    stream.read()
                    fail("Expected InterruptedIOException to be thrown")
                } catch (e: InterruptedIOException) {
                    assertTrue(Thread.currentThread().isInterrupted, "Interrupted status should be restored/preserved")
                    assertTrue(e.message!!.contains("Thread [${Thread.currentThread().name}]"), "Exception message should contain the thread name")
                } finally {
                    Thread.interrupted()
                }
            }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `readWithRetry should throw InterruptedIOException and restore interrupt status when thread is interrupted`() {
        val mock = MockNativeEngine(
            memory = object : MockNativeMemory() {
                override fun read(
                    fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    buf: ManagedSegment,
                    count: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    return LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(4, -1L)
                }
            },
        )

        run {
            NativeArena.ofConfined().use { arena ->
                val stream = NativeSocketInputStream(
                    TestFileDescriptors
                    .replace<FileDescriptorRole.Generic>(1),
                        arena,
                    mock.memory,
                )
                val buffer = ByteArray(1)
                Thread.currentThread().interrupt()
                try {
                    stream.read(buffer)
                    fail("Expected InterruptedIOException to be thrown")
                } catch (e: InterruptedIOException) {
                    assertTrue(Thread.currentThread().isInterrupted, "Interrupted status should be restored/preserved")
                    assertTrue(e.message!!.contains("Thread [${Thread.currentThread().name}]"), "Exception message should contain the thread name")
                } finally {
                    Thread.interrupted()
                }
            }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `read should trigger progressive backoff on consecutive EINTRs and throw InterruptedIOException if interrupted during sleep`() {
        var callCount = 0
        val mock = MockNativeEngine(
            memory = object : MockNativeMemory() {
                override fun read(
                    fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    buf: ManagedSegment,
                    count: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    callCount++
                    if (callCount == 4) {
                        Thread.currentThread().interrupt()
                    }
                    return LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(4, -1L)
                }
            },
        )

        run {
            NativeArena.ofConfined().use { arena ->
                val stream = NativeSocketInputStream(
                    TestFileDescriptors
                    .replace<FileDescriptorRole.Generic>(1),
                        arena,
                    mock.memory,
                )
                try {
                    stream.read()
                    fail("Expected InterruptedIOException from sleep interruption")
                } catch (e: InterruptedIOException) {
                    assertTrue(Thread.currentThread().isInterrupted, "Interrupted status should be restored/preserved")
                } finally {
                    Thread.interrupted()
                }
            }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `read should return -1 on EOF`() {
        val mock = MockNativeEngine(
            memory = object : MockNativeMemory() {
                override fun read(
                    fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    buf: ManagedSegment,
                    count: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    return LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
                }
            },
        )
        run {
            NativeArena.ofConfined().use { arena ->
                val stream = NativeSocketInputStream(
                    TestFileDescriptors
                    .replace<FileDescriptorRole.Generic>(1),
                        arena,
                    mock.memory,
                )
                assertEquals(-1, stream.read())
            }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `read should return -1 on non-EINTR error`() {
        val mock = MockNativeEngine(
            memory = object : MockNativeMemory() {
                override fun read(
                    fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    buf: ManagedSegment,
                    count: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    return LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(22, -1L) // EINVAL (22)
                }
            },
        )
        run {
            NativeArena.ofConfined().use { arena ->
                val stream = NativeSocketInputStream(
                    TestFileDescriptors
                    .replace<FileDescriptorRole.Generic>(1),
                        arena,
                    mock.memory,
                )
                assertEquals(-1, stream.read())
            }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `bulk read with length 0 should return 0`() {
        NativeArena.ofConfined().use { arena ->
            val stream = NativeSocketInputStream(
                TestFileDescriptors
                .replace<FileDescriptorRole.Generic>(1),
                    arena,
            )
            val buffer = ByteArray(5)
            assertEquals(0, stream.read(buffer, 0, 0))
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `bulk read should return -1 on EOF`() {
        val mock = MockNativeEngine(
            memory = object : MockNativeMemory() {
                override fun read(
                    fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    buf: ManagedSegment,
                    count: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    return LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
                }
            },
        )
        run {
            NativeArena.ofConfined().use { arena ->
                val stream = NativeSocketInputStream(
                    TestFileDescriptors
                    .replace<FileDescriptorRole.Generic>(1),
                        arena,
                    mock.memory,
                )
                val buffer = ByteArray(5)
                assertEquals(-1, stream.read(buffer))
            }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `bulk read should return -1 on non-EINTR error`() {
        val mock = MockNativeEngine(
            memory = object : MockNativeMemory() {
                override fun read(
                    fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    buf: ManagedSegment,
                    count: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    return LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(22, -1L) // EINVAL (22)
                }
            },
        )
        run {
            NativeArena.ofConfined().use { arena ->
                val stream = NativeSocketInputStream(
                    TestFileDescriptors
                    .replace<FileDescriptorRole.Generic>(1),
                        arena,
                    mock.memory,
                )
                val buffer = ByteArray(5)
                assertEquals(-1, stream.read(buffer))
            }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `read should trigger progressive backoff on consecutive EINTRs and complete successfully`() {
        var callCount = 0
        val mock = MockNativeEngine(
            memory = object : MockNativeMemory() {
                override fun read(
                    fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    buf: ManagedSegment,
                    count: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    callCount++
                    return if (callCount <= 5) {
                        LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(4, -1L)
                    } else {
                        ManagedSegment.copy(byteArrayOf(0x42.toByte()), 0, buf, 0L, 1)
                        LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(1L)
                    }
                }
            },
        )

        run {
            NativeArena.ofConfined().use { arena ->
                val stream = NativeSocketInputStream(
                    TestFileDescriptors
                    .replace<FileDescriptorRole.Generic>(1),
                        arena,
                    mock.memory,
                )
                assertEquals(0x42, stream.read())
                assertEquals(6, callCount)
            }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `bulk read should trigger progressive backoff on consecutive EINTRs and complete successfully`() {
        var callCount = 0
        val mock = MockNativeEngine(
            memory = object : MockNativeMemory() {
                override fun read(
                    fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    buf: ManagedSegment,
                    count: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    callCount++
                    return if (callCount <= 5) {
                        LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(4, -1L)
                    } else {
                        ManagedSegment.copy(byteArrayOf(0x42.toByte()), 0, buf, 0L, 1)
                        LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(1L)
                    }
                }
            },
        )

        run {
            NativeArena.ofConfined().use { arena ->
                val stream = NativeSocketInputStream(
                    TestFileDescriptors
                    .replace<FileDescriptorRole.Generic>(1),
                        arena,
                    mock.memory,
                )
                val buffer = ByteArray(1)
                assertEquals(1, stream.read(buffer))
                assertEquals(0x42.toByte(), buffer[0])
                assertEquals(6, callCount)
            }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `readWithRetry should trigger progressive backoff on consecutive EINTRs and throw InterruptedIOException if interrupted during sleep`() {
        var callCount = 0
        val mock = MockNativeEngine(
            memory = object : MockNativeMemory() {
                override fun read(
                    fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    buf: ManagedSegment,
                    count: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    callCount++
                    if (callCount == 4) {
                        Thread.currentThread().interrupt()
                    }
                    return LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(4, -1L)
                }
            },
        )

        run {
            NativeArena.ofConfined().use { arena ->
                val stream = NativeSocketInputStream(
                    TestFileDescriptors
                    .replace<FileDescriptorRole.Generic>(1),
                        arena,
                    mock.memory,
                )
                val buffer = ByteArray(1)
                try {
                    stream.read(buffer)
                    fail("Expected InterruptedIOException from sleep interruption")
                } catch (e: InterruptedIOException) {
                    assertTrue(Thread.currentThread().isInterrupted, "Interrupted status should be restored/preserved")
                } finally {
                    Thread.interrupted()
                }
            }
        }
    }
}
