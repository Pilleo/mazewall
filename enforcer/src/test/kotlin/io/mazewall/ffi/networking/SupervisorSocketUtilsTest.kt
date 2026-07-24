package io.mazewall.ffi.networking

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeNetworking
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.memory.CmsghdrSegment
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.MsghdrSegment
import io.mazewall.ffi.memory.native
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.charset.StandardCharsets

class SupervisorSocketUtilsTest {

    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
    }

    @Test
    fun `test setupSockAddrUn`() {
        io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
            val path = "/tmp/test.sock"
            val sockaddr = SupervisorSocketUtils.setupSockAddrUn(arena, path)

            assertEquals(SupervisorSocketUtils.AF_UNIX.toShort(), sockaddr.getSunFamily())

            val pathSegment = sockaddr.getSunPath()
            val storedPath = pathSegment.getString(0, StandardCharsets.UTF_8)
            assertEquals(path, storedPath)
        }
    }

    @Test
    fun `test setupSockAddrUn with path of maximum allowed length`() {
        io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
            val path = "a".repeat(107)
            val sockaddr = SupervisorSocketUtils.setupSockAddrUn(arena, path)

            assertEquals(SupervisorSocketUtils.AF_UNIX.toShort(), sockaddr.getSunFamily())

            val pathSegment = sockaddr.getSunPath()
            val storedPath = pathSegment.getString(0, StandardCharsets.UTF_8)
            assertEquals(path, storedPath)
        }
    }

    @Test
    fun `test setupSockAddrUn with path too long throws IllegalArgumentException`() {
        io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
            val path108 = "a".repeat(108)
            val exception108 = assertThrows<IllegalArgumentException> {
                SupervisorSocketUtils.setupSockAddrUn(arena, path108)
            }
            assertEquals("Socket path too long: $path108 (length: 108, max: 107)", exception108.message)

            val path109 = "a".repeat(109)
            val exception109 = assertThrows<IllegalArgumentException> {
                SupervisorSocketUtils.setupSockAddrUn(arena, path109)
            }
            assertEquals("Socket path too long: $path109 (length: 109, max: 107)", exception109.message)
        }
    }

    @Test
    fun `sendDescriptor retries on EINTR and eventually succeeds`() {
        var callCount = 0
        val mockNetworking = object : MockNativeNetworking() {
            override fun sendmsg(
                sockfd: FileDescriptor<*, FdState.Open>,
                msg: ManagedSegment,
                flags: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                callCount++
                return if (callCount < 3) {
                    LinuxNative.SyscallResult.Error(io.mazewall.ffi.NativeConstants.EINTR, -1L)
                } else {
                    LinuxNative.SyscallResult.Success(1L)
                }
            }
        }

        val mockEngine = object : MockNativeEngine(networking = mockNetworking) {}
        LinuxNative.setEngine(mockEngine)

        val success = SupervisorSocketUtils.sendDescriptor(10, 11)
        assertTrue(success)
        assertEquals(3, callCount)
    }

    @Test
    fun `sendDescriptor returns false on non-EINTR error`() {
        var callCount = 0
        val mockNetworking = object : MockNativeNetworking() {
            override fun sendmsg(
                sockfd: FileDescriptor<*, FdState.Open>,
                msg: ManagedSegment,
                flags: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                callCount++
                return LinuxNative.SyscallResult.Error(13, -1L) // EACCES
            }
        }

        val mockEngine = object : MockNativeEngine(networking = mockNetworking) {}
        LinuxNative.setEngine(mockEngine)

        val success = SupervisorSocketUtils.sendDescriptor(10, 11)
        assertFalse(success)
        assertEquals(1, callCount)
    }

    @Test
    fun `recvDescriptor retries on EINTR and eventually succeeds`() {
        var callCount = 0
        val mockNetworking = object : MockNativeNetworking() {
            override fun recvmsg(
                sockfd: FileDescriptor<*, FdState.Open>,
                msg: ManagedSegment,
                flags: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                callCount++
                if (callCount < 3) {
                    return LinuxNative.SyscallResult.Error(io.mazewall.ffi.NativeConstants.EINTR, -1L)
                }

                val rawMsgSegment = msg.native
                val msghdr = MsghdrSegment(rawMsgSegment)
                val controlSegment = msghdr.getMsgControl().reinterpret(SupervisorSocketUtils.MSG_CONTROL_BUF_SIZE)
                val cmsghdr = CmsghdrSegment(controlSegment)
                cmsghdr.setCmsgLen(SupervisorSocketUtils.CMSG_RIGHTS_LEN)
                cmsghdr.setCmsgLevel(SupervisorSocketUtils.SOL_SOCKET)
                cmsghdr.setCmsgType(SupervisorSocketUtils.SCM_RIGHTS)
                cmsghdr.setDataFd(42)

                return LinuxNative.SyscallResult.Success(1L)
            }
        }

        val mockEngine = object : MockNativeEngine(networking = mockNetworking) {}
        LinuxNative.setEngine(mockEngine)

        val socketFd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10)
        val result = SupervisorSocketUtils.recvDescriptor(socketFd)
        assertEquals(42, result?.value)
        assertEquals(3, callCount)
    }

    @Test
    fun `recvDescriptor returns null on non-EINTR error`() {
        var callCount = 0
        val mockNetworking = object : MockNativeNetworking() {
            override fun recvmsg(
                sockfd: FileDescriptor<*, FdState.Open>,
                msg: ManagedSegment,
                flags: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                callCount++
                return LinuxNative.SyscallResult.Error(13, -1L) // EACCES
            }
        }

        val mockEngine = object : MockNativeEngine(networking = mockNetworking) {}
        LinuxNative.setEngine(mockEngine)

        val socketFd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10)
        val result = SupervisorSocketUtils.recvDescriptor(socketFd)
        assertNull(result)
        assertEquals(1, callCount)
    }

    @Test
    fun `connectWithRetry specifies SOCK_CLOEXEC`() {
        var socketTypeArg = 0
        val mockNetworking = object : MockNativeNetworking() {
            override fun socket(
                domain: Int,
                type: Int,
                protocol: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                socketTypeArg = type
                return LinuxNative.SyscallResult.Error(111, -1L) // Fail immediately to stop retries
            }
        }

        val mockEngine = object : MockNativeEngine(networking = mockNetworking) {}
        LinuxNative.setEngine(mockEngine)

        assertThrows<IllegalStateException> {
            SupervisorSocketUtils.connectWithRetry("/tmp/test_nonexistent.sock", maxRetries = 1)
        }

        val expectedType = SupervisorSocketUtils.SOCK_STREAM or io.mazewall.ffi.NativeConstants.SOCK_CLOEXEC
        assertEquals(expectedType, socketTypeArg)
    }

    @Test
    fun `RealSocketManager createUnixServer specifies SOCK_CLOEXEC`() {
        var socketTypeArg = 0
        val mockNetworking = object : MockNativeNetworking() {
            override fun socket(
                domain: Int,
                type: Int,
                protocol: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                socketTypeArg = type
                return LinuxNative.SyscallResult.Error(111, -1L) // Fail to prevent actual bind/listen
            }
        }

        val mockEngine = object : MockNativeEngine(networking = mockNetworking) {}
        LinuxNative.setEngine(mockEngine)

        assertThrows<IllegalStateException> {
            io.mazewall.core.RealSocketManager.createUnixServer("/tmp/test_server.sock")
        }

        val expectedType = SupervisorSocketUtils.SOCK_STREAM or io.mazewall.ffi.NativeConstants.SOCK_CLOEXEC
        assertEquals(expectedType, socketTypeArg)
    }

    @Test
    fun `RealSocketManager accept calls accept4 with SOCK_CLOEXEC`() {
        var accept4Flags = 0
        val mockNetworking = object : MockNativeNetworking() {
            override fun accept4(
                sockfd: FileDescriptor<*, FdState.Open>,
                addr: ManagedSegment,
                addrlen: ManagedSegment,
                flags: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                accept4Flags = flags
                return LinuxNative.SyscallResult.Success(99L)
            }
        }

        val mockEngine = object : MockNativeEngine(networking = mockNetworking) {}
        LinuxNative.setEngine(mockEngine)

        val serverFd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10)
        val clientFd = io.mazewall.core.RealSocketManager.accept(serverFd)

        assertEquals(99, clientFd.value)
        assertEquals(io.mazewall.ffi.NativeConstants.SOCK_CLOEXEC, accept4Flags)
    }
}