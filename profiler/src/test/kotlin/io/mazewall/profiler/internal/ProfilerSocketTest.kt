package io.mazewall.profiler.internal

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeFileSystem
import io.mazewall.MockNativeNetworking
import io.mazewall.NativeTransaction
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.networking.SupervisorSocketUtils
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

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
            context(context: NativeTransaction)
            override fun socket(
                domain: Int,
                type: Int,
                protocol: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                socketTypeArg = type
                return LinuxNative.SyscallResult.Success(99L)
            }

            context(context: NativeTransaction)
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
            context(context: NativeTransaction)
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

    @Test
    fun `test setupSockAddrUn setup correctly`() {
        NativeArena.ofConfined().use { arena ->
            val path = "/tmp/profiler.sock"
            val sockaddr = ProfilerSocket.setupSockAddrUn(arena, path)

            assertEquals(SupervisorSocketUtils.AF_UNIX.toShort(), sockaddr.getSunFamily())

            val pathSegment = sockaddr.getSunPath()
            val storedPath = pathSegment.getString(0, StandardCharsets.UTF_8)
            assertEquals(path, storedPath)
        }
    }
}
