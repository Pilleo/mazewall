package io.mazewall.profiler.internal

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeFileSystem
import io.mazewall.MockNativeNetworking
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.testAcquisition
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.networking.SupervisorSocketUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProfilerSocketTest {
    @Test
    fun `socket operations require an explicit native engine`() {
        val methods = ProfilerSocket::class.java.declaredMethods
            .filterNot { it.name.endsWith("\$default") }

        assertFalse(
            methods.any { it.name == "connectDescriptorWithRetry" && it.parameterCount == 3 },
            "connection setup must not snapshot the process-global native engine",
        )
        assertFalse(
            methods.any { it.name == "sendDescriptor" && it.parameterCount == 2 },
            "descriptor sending must not snapshot the process-global native engine",
        )
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
                return LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(99L).testAcquisition()
            }

            override fun connect(
                sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                addr: ManagedSegment,
                addrlen: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                connectCalled = true
                return LinuxNative.SyscallResult.Success(0L)
            }
        }

        val mockEngine = object : MockNativeEngine(networking = mockNetworking) {}
        val fd = ProfilerSocket.connectDescriptorWithRetry("/tmp/test_profiler.sock", mockEngine, maxRetries = 1)
        assertEquals(99, fd.value)
        assertTrue(connectCalled)

        val expectedType = SupervisorSocketUtils.SOCK_STREAM or io.mazewall.ffi.NativeConstants.SOCK_CLOEXEC
        assertEquals(expectedType, socketTypeArg)
    }

    @Test
    fun `test sendDescriptor delegates and succeeds`() {
        var sendmsgCalled = false
        val mockNetworking = object : MockNativeNetworking() {
            override fun sendmsg(
                sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                msg: ManagedSegment,
                flags: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                sendmsgCalled = true
                return LinuxNative.SyscallResult.Success(1L)
            }
        }

        val mockEngine = object : MockNativeEngine(networking = mockNetworking) {}
        val success = ProfilerSocket.sendDescriptor(10, 11, mockEngine)
        assertTrue(success)
        assertTrue(sendmsgCalled)
    }
}
