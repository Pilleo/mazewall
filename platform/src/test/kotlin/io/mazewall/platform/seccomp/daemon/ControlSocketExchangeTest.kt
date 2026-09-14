package io.mazewall.platform.seccomp.daemon

import io.mazewall.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.api.MockNativeMemory
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.ffi.memory.writeByte
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.Deadline
import io.mazewall.platform.lifecycle.TestFileDescriptors
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
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
        val engine = MockNativeEngine(memory = memory).apply {
            onPoll = { fds, _, _ ->
                PollFdSegment.of(fds).setRevents((NativeConstants.POLLIN.toInt() or 4).toShort())
                LinuxNative.SyscallResult.Success(1L)
            }
        }
        val exchange =
            ControlSocketExchange(
                engine.raw,
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
        var now = 0L
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
        val engine = MockNativeEngine(memory = memory).apply {
            onPoll = { fds, _, _ ->
                PollFdSegment.of(fds).setRevents(4)
                LinuxNative.SyscallResult.Success(1L)
            }
        }
        val exchange =
            ControlSocketExchange(engine.raw, memory, TestFileDescriptors.replace<FileDescriptorRole.UnixSocket>(10), byteArrayOf(1), 0, Deadline(8_000_000_000L)) { now }
        NativeArena.ofConfined().use { arena ->
            with(arena) {
            repeat(8) {
                assertNull(exchange.poll())
                now += 1_000_000_000L
            }
            assertEquals(NativeConstants.ETIMEDOUT, assertThrows<ControlSocketFailure> { exchange.poll() }.errno)
            assertEquals(8, calls)
        }
        }
    }

    @Test
    fun `EAGAIN retry waits for POLLOUT readiness`() {
        var writes = 0
        var polls = 0
        val memory = object : MockNativeMemory() {
            override fun write(
                fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                buf: ManagedSegment,
                count: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                writes++
                return if (writes == 1) LinuxNative.SyscallResult.Error(NativeConstants.EAGAIN, -1L) else LinuxNative.SyscallResult.Success(1L)
            }
        }
        val engine = MockNativeEngine(memory = memory).apply {
            onPoll = { fds, _, _ ->
                polls++
                PollFdSegment.of(fds).setRevents(if (polls == 2) 0 else 4)
                LinuxNative.SyscallResult.Success(if (polls == 2) 0L else 1L)
            }
        }
        val exchange = ControlSocketExchange(
            engine.raw,
            memory,
            TestFileDescriptors.replace<FileDescriptorRole.UnixSocket>(10),
            byteArrayOf(1),
            0,
            Deadline.afterMillis(30_000),
        )

        NativeArena.ofConfined().use { arena ->
            with(arena) {
                assertNull(exchange.poll())
                assertNull(exchange.poll())
                assertEquals(1, writes)
                assertArrayEquals(byteArrayOf(), exchange.poll())
                assertEquals(2, writes)
            }
        }
    }
}
