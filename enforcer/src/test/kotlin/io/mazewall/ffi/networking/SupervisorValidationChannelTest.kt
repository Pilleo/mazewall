package io.mazewall.ffi.networking

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeMemory
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.FdState
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.memory.SupervisorResponseSegment
import io.mazewall.ffi.memory.readByte
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SupervisorValidationChannelTest {

    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
    }

    @Test
    fun `sendResponse writes correct decision and error to native memory`() {
        var writtenFd: FileDescriptor<*, *>? = null
        var writtenCount: Long? = null
        var capturedId: Long? = null
        var capturedDecision: Byte? = null
        var capturedErrorNr: Int? = null
        var capturedPath: String? = null

        val mockMemory = object : MockNativeMemory() {
            override fun write(
                fd: FileDescriptor<*, FdState.Open>,
                buf: io.mazewall.ffi.memory.ManagedSegment,
                count: Long
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
        LinuxNative.setEngine(mockEngine)

        val socketFd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(42)
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
                fd: FileDescriptor<*, FdState.Open>,
                buf: io.mazewall.ffi.memory.ManagedSegment,
                count: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                writtenCount = count
                ack = buf.readByte(0)
                return LinuxNative.SyscallResult.Success(count)
            }
        }
        LinuxNative.setEngine(object : MockNativeEngine(memory = mockMemory) {})
        val channel = SupervisorValidationChannel(FileDescriptor.unsafe(7))
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
                fd: FileDescriptor<*, FdState.Open>,
                buf: io.mazewall.ffi.memory.ManagedSegment,
                count: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                writtenCount = count
                ack = buf.readByte(0)
                return LinuxNative.SyscallResult.Success(count)
            }
        }
        LinuxNative.setEngine(object : MockNativeEngine(memory = mockMemory) {})
        val channel = SupervisorValidationChannel(FileDescriptor.unsafe(7))
        channel.sendExecRewriteAck(false)
        assertEquals(1L, writtenCount)
        assertEquals(0.toByte(), ack)
        channel.close()
    }
}
