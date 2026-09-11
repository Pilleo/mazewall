package io.mazewall.ffi.networking

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeMemory
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.TestFileDescriptors
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.SupervisorResponseSegment
import io.mazewall.ffi.memory.readByte
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

@org.junit.jupiter.api.extension.ExtendWith(io.mazewall.core.ForeignFdGuard::class)
class SupervisorValidationChannelTest {
    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
        System.clearProperty("mazewall.supervisor.installTimeoutMillis")
    }

    @Test
    fun `validation socket is configured nonblocking before use`() {
        var configuredFlags = 0L
        val engine = object : MockNativeEngine() {
            override fun fcntl(
                fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                cmd: Int,
                arg: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                if (cmd == NativeConstants.F_SETFL) configuredFlags = arg
                return LinuxNative.SyscallResult.Success(0L)
            }
        }
        LinuxNative.setEngine(engine)

        SupervisorValidationChannel(TestFileDescriptors.replace(7)).close()

        assertTrue((configuredFlags and NativeConstants.O_NONBLOCK) != 0L)
    }

    @Test
    fun `stalled validation peer is bounded by the installation deadline`() {
        System.setProperty("mazewall.supervisor.installTimeoutMillis", "7")
        val pollTimeouts = mutableListOf<Int>()
        val engine = MockNativeEngine().apply {
            onPoll = { _, _, timeout ->
                pollTimeouts += timeout
                if (pollTimeouts.size == 1) {
                    LinuxNative.SyscallResult.Success(0L)
                } else {
                    LinuxNative.SyscallResult.Error(NativeConstants.EIO, -1L)
                }
            }
        }
        LinuxNative.setEngine(engine)
        val channel = SupervisorValidationChannel(TestFileDescriptors.replace(7))

        val failure = assertThrows<java.io.IOException> { channel.inputStream.read() }

        assertTrue(failure.message.orEmpty().contains("timed out"))
        assertEquals(1, pollTimeouts.size)
        assertTrue(pollTimeouts.single() in 1..7)
        channel.close()
    }

    @Test
    fun `repeated EINTR reads retain one absolute deadline`() {
        System.setProperty("mazewall.supervisor.installTimeoutMillis", "7")
        var reads = 0
        val memory = object : MockNativeMemory() {
            override fun read(
                fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                buf: io.mazewall.ffi.memory.ManagedSegment,
                count: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                reads++
                return LinuxNative.SyscallResult.Error(NativeConstants.EINTR, -1L)
            }
        }
        val engine = MockNativeEngine(memory = memory).apply {
            onPoll = { fds, _, _ ->
                io.mazewall.ffi.memory.PollFdSegment
                    .of(fds)
                    .setRevents(NativeConstants.POLLIN)
                LinuxNative.SyscallResult.Success(1L)
            }
        }
        LinuxNative.setEngine(engine)
        val channel = SupervisorValidationChannel(TestFileDescriptors.replace(7))

        val failure = assertThrows<IOException> { channel.inputStream.read() }

        assertTrue(failure.message.orEmpty().contains("timed out"))
        assertTrue(reads > 1)
        channel.close()
    }

    @Test
    fun `sendResponse writes correct decision and error to native memory`() {
        var writtenFd: FileDescriptor<*, *, FdOwnership>? = null
        var writtenCount: Long? = null
        var capturedId: Long? = null
        var capturedDecision: Byte? = null
        var capturedErrorNr: Int? = null
        var capturedPath: String? = null

        val mockMemory = object : MockNativeMemory() {
            override fun write(
                fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                buf: io.mazewall.ffi.memory.ManagedSegment,
                count: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                writtenFd = fd
                writtenCount = count
                val resp = SupervisorResponseSegment.of(buf)
                capturedId = resp.getId()
                capturedDecision = resp.getDecision()
                capturedErrorNr = resp.getErrorNr()
                capturedPath = resp.getPath()
                return LinuxNative.SyscallResult.Success(count)
            }
        }

        val mockEngine = object : MockNativeEngine(memory = mockMemory) {}
        mockEngine.onPoll = { fds, _, _ ->
            io.mazewall.ffi.memory.PollFdSegment
                .of(fds)
                .setRevents(NativeConstants.POLLOUT)
            LinuxNative.SyscallResult.Success(1L)
        }
        LinuxNative.setEngine(mockEngine)

        val socketFd = TestFileDescriptors
            .replace<FileDescriptorRole.UnixSocket>(42)
        val channel = SupervisorValidationChannel(socketFd)

        channel.sendResponse(1001L, 2.toByte(), 13, "/usr/bin/true")

        assertEquals(socketFd, writtenFd)
        assertEquals(Layouts.SUPERVISOR_RESPONSE_SIZE, writtenCount)
        assertEquals(1001L, capturedId)
        assertEquals(2.toByte(), capturedDecision)
        assertEquals(0, capturedErrorNr)
        assertEquals("/usr/bin/true", capturedPath)

        channel.sendResponse(1002L, 0.toByte(), 13, null)
        assertEquals(0.toByte(), capturedDecision)
        assertEquals(13, capturedErrorNr)

        // Clean up
        channel.close()
    }

    @Test
    fun `sendExecRewriteAck writes the ack byte fully`() {
        var writtenCount: Long? = null
        var ack: Byte? = null
        val mockMemory = object : MockNativeMemory() {
            override fun write(
                fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                buf: io.mazewall.ffi.memory.ManagedSegment,
                count: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                writtenCount = count
                ack = buf.readByte(0)
                return LinuxNative.SyscallResult.Success(count)
            }
        }
        LinuxNative.setEngine(
            object : MockNativeEngine(memory = mockMemory) {}.apply {
            onPoll = { fds, _, _ ->
                io.mazewall.ffi.memory.PollFdSegment
                    .of(fds)
                    .setRevents(NativeConstants.POLLOUT)
                LinuxNative.SyscallResult.Success(1L)
            }
        },
        )
        val channel = SupervisorValidationChannel(
            TestFileDescriptors
            .replace(7),
        )
        channel.sendExecRewriteAck(true)
        assertEquals(1L, writtenCount)
        assertEquals(1.toByte(), ack)
        channel.close()
    }

    @Test
    fun `sendExecRewriteAck(false) writes 0`() {
        var writtenCount: Long? = null
        var ack: Byte? = null
        val mockMemory = object : MockNativeMemory() {
            override fun write(
                fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                buf: io.mazewall.ffi.memory.ManagedSegment,
                count: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                writtenCount = count
                ack = buf.readByte(0)
                return LinuxNative.SyscallResult.Success(count)
            }
        }
        LinuxNative.setEngine(
            object : MockNativeEngine(memory = mockMemory) {}.apply {
            onPoll = { fds, _, _ ->
                io.mazewall.ffi.memory.PollFdSegment
                    .of(fds)
                    .setRevents(NativeConstants.POLLOUT)
                LinuxNative.SyscallResult.Success(1L)
            }
        },
        )
        val channel = SupervisorValidationChannel(
            TestFileDescriptors
            .replace(7),
        )
        channel.sendExecRewriteAck(false)
        assertEquals(1L, writtenCount)
        assertEquals(0.toByte(), ack)
        channel.close()
    }
}
