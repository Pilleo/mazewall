package io.mazewall.ffi.networking

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeMemory
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.FdState
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.memory.SupervisorResponseSegment
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
                return LinuxNative.SyscallResult.Success(count)
            }
        }

        val mockEngine = object : MockNativeEngine(memory = mockMemory) {}
        LinuxNative.setEngine(mockEngine)

        val socketFd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(42)
        val channel = SupervisorValidationChannel(socketFd)

        channel.sendResponse(1001L, 2.toByte(), 13) // ID=1001, Decision=2 (Allow & Inject FD), ErrorNr=13 (EACCES)

        assertEquals(socketFd, writtenFd)
        assertEquals(Layouts.SUPERVISOR_RESPONSE_SIZE, writtenCount)
        assertEquals(1001L, capturedId)
        assertEquals(2.toByte(), capturedDecision)
        assertEquals(13, capturedErrorNr)

        // Clean up
        channel.close()
    }
}
