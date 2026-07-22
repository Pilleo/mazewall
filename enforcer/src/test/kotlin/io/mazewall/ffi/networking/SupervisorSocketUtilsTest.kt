package io.mazewall.ffi.networking

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeNetworking
import io.mazewall.NativeTransaction
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
            context(_: NativeTransaction)
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
            context(_: NativeTransaction)
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
            context(_: NativeTransaction)
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
            context(_: NativeTransaction)
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
}