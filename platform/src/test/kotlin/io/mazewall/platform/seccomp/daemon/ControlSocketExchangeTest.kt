package io.mazewall.platform.seccomp.daemon

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeMemory
import io.mazewall.core.Deadline
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.TestFileDescriptors
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.writeByte
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ControlSocketExchangeTest {
    @Test
    fun `short writes and read wait keep one deadline`() {
        var now = 0L
        var written = 0L
        var readCalls = 0
        val memory = object : MockNativeMemory() {
            override fun write(
                fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                buf: ManagedSegment,
                count: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                written++
                return LinuxNative.SyscallResult.Success(1L)
            }

            override fun read(
                fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                buf: ManagedSegment,
                count: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                readCalls++
                buf.writeByte(0, 0xAC.toByte())
                return LinuxNative.SyscallResult.Success(1L)
            }
        }
        val exchange =
            ControlSocketExchange(
                MockNativeEngine(memory = memory).raw,
                memory,
                TestFileDescriptors.replace<FileDescriptorRole.UnixSocket>(10),
                byteArrayOf(1, 2),
                1,
                Deadline(30_000_000_000L),
            ) { now }
        NativeArena.ofConfined().use { arena ->
            with(arena) {
            assertNull(exchange.poll())
            now = 29_000_000_000L
            assertNull(exchange.poll())
            now = 30_000_000_000L
            assertThrows<ControlSocketFailure> { exchange.poll() }
            assertEquals(2L, written)
            assertEquals(0, readCalls)
        }
        }
    }

    @Test
    fun `EAGAIN yields and persistent EINTR fails with no unbounded loop`() {
        var calls = 0
        val memory = object : MockNativeMemory() {
            override fun write(
                fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                buf: ManagedSegment,
                count: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                calls++
                return LinuxNative.SyscallResult.Error(if (calls == 1) NativeConstants.EAGAIN else NativeConstants.EINTR, -1L)
            }
        }
        val exchange =
            ControlSocketExchange(MockNativeEngine(memory = memory).raw, memory, TestFileDescriptors.replace<FileDescriptorRole.UnixSocket>(10), byteArrayOf(1), 0, Deadline(30_000_000_000L)) { 0L }
        NativeArena.ofConfined().use { arena ->
            with(arena) {
            repeat(8) { assertNull(exchange.poll()) }
            assertEquals(NativeConstants.EINTR, assertThrows<ControlSocketFailure> { exchange.poll() }.errno)
            assertEquals(9, calls)
        }
        }
    }
}
