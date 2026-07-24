package io.mazewall.profiler.internal

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeFileSystem
import io.mazewall.MockNativeNetworking
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.networking.SupervisorSocketUtils
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProfilerSocketTest {

    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
    }

    @Test
    fun `test connectWithRetry delegates and specifies SOCK_CLOEXEC`() {
        var socketTypeArg = 0
        var connectCalled = false

        val mockNetworking = object : MockNativeNetworking() {
            override fun socket(
                domain: Int,
                type: Int,
                protocol: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                socketTypeArg = type
                return LinuxNative.SyscallResult.Success(99L)
            }

            override fun connect(
                sockfd: FileDescriptor<*, FdState.Open>,
                addr: ManagedSegment,
                addrlen: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                connectCalled = true
                return LinuxNative.SyscallResult.Success(0L)
            }
        }

        val mockEngine = object : MockNativeEngine(networking = mockNetworking) {}
        LinuxNative.setEngine(mockEngine)

        val fd = ProfilerSocket.connectWithRetry("/tmp/test_profiler.sock", maxRetries = 1)
        assertEquals(99, fd)
        assertTrue(connectCalled)

        val expectedType = SupervisorSocketUtils.SOCK_STREAM or io.mazewall.ffi.NativeConstants.SOCK_CLOEXEC
        assertEquals(expectedType, socketTypeArg)
    }

    @Test
    fun `test sendDescriptor delegates and succeeds`() {
        var sendmsgCalled = false
        val mockNetworking = object : MockNativeNetworking() {
            override fun sendmsg(
                sockfd: FileDescriptor<*, FdState.Open>,
                msg: ManagedSegment,
                flags: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                sendmsgCalled = true
                return LinuxNative.SyscallResult.Success(1L)
            }
        }

        val mockEngine = object : MockNativeEngine(networking = mockNetworking) {}
        LinuxNative.setEngine(mockEngine)

        val success = ProfilerSocket.sendDescriptor(10, 11)
        assertTrue(success)
        assertTrue(sendmsgCalled)
    }
}
