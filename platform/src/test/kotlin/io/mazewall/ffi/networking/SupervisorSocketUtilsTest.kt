package io.mazewall.ffi.networking

import io.mazewall.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.api.MockNativeNetworking
import io.mazewall.ffi.memory.CmsghdrSegment
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.MsghdrSegment
import io.mazewall.ffi.memory.native
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.TestFileDescriptors
import io.mazewall.platform.lifecycle.testAcquisition
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.InvocationTargetException
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
                sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
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
                sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
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
                sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
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

        val socketFd = TestFileDescriptors
            .replace<FileDescriptorRole.UnixSocket>(10)
        val result = SupervisorSocketUtils.recvDescriptor(socketFd)
        assertEquals(42, result?.value)
        assertEquals(3, callCount)
    }

    @Test
    fun `recvDescriptor returns null on non-EINTR error`() {
        var callCount = 0
        val mockNetworking = object : MockNativeNetworking() {
            override fun recvmsg(
                sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                msg: ManagedSegment,
                flags: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                callCount++
                return LinuxNative.SyscallResult.Error(13, -1L) // EACCES
            }
        }

        val mockEngine = object : MockNativeEngine(networking = mockNetworking) {}
        LinuxNative.setEngine(mockEngine)

        val socketFd = TestFileDescriptors
            .replace<FileDescriptorRole.UnixSocket>(10)
        val result = SupervisorSocketUtils.recvDescriptor(socketFd)
        assertNull(result)
        assertEquals(1, callCount)
    }

    @Test
    fun `recvDescriptor uses the supplied native runtime`() {
        var receiveCalls = 0
        val suppliedRuntime = MockNativeEngine(
            networking = object : MockNativeNetworking() {
                override fun recvmsg(
                    sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    msg: ManagedSegment,
                    flags: Int,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    receiveCalls++
                    return LinuxNative.SyscallResult.Error(io.mazewall.ffi.NativeConstants.EACCES, -1L)
                }
            },
        )
        val overload = SupervisorSocketUtils::class.java.declaredMethods.singleOrNull { method ->
            method.name == "recvDescriptor" &&
                method.parameterTypes.size == 4 &&
                method.parameterTypes[2] == NativeEngine::class.java
        }

        assertTrue(overload != null, "descriptor receive must accept an explicitly captured NativeEngine")
        val result = overload!!.invoke(
            SupervisorSocketUtils,
            TestFileDescriptors.replace<FileDescriptorRole.UnixSocket>(10),
            FileDescriptorRole.SeccompNotif,
            suppliedRuntime,
            null,
        )

        assertNull(result)
        assertEquals(1, receiveCalls)
    }

    @Test
    fun `recvDescriptor returns null after a successful message without SCM_RIGHTS`() {
        var callCount = 0
        val mockNetworking = object : MockNativeNetworking() {
            override fun recvmsg(
                sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                msg: ManagedSegment,
                flags: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                callCount++
                return LinuxNative.SyscallResult.Success(1L)
            }
        }

        LinuxNative.setEngine(object : MockNativeEngine(networking = mockNetworking) {})

        val socketFd = TestFileDescriptors
            .replace<FileDescriptorRole.UnixSocket>(10)
        assertNull(SupervisorSocketUtils.recvDescriptor(socketFd))
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
            SupervisorSocketUtils.connectDescriptorWithRetry("/tmp/test_nonexistent.sock", maxRetries = 1)
        }

        val expectedType = SupervisorSocketUtils.SOCK_STREAM or io.mazewall.ffi.NativeConstants.SOCK_CLOEXEC
        assertEquals(expectedType, socketTypeArg)
    }

    @Test
    fun `connectDescriptorWithRetry uses the supplied native runtime`() {
        var socketCalls = 0
        val suppliedRuntime = MockNativeEngine(
            networking = object : MockNativeNetworking() {
                override fun socket(
                    domain: Int,
                    type: Int,
                    protocol: Int,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    socketCalls++
                    return LinuxNative.SyscallResult.Error(io.mazewall.ffi.NativeConstants.EACCES, -1L)
                }
            },
        )
        val overload = SupervisorSocketUtils::class.java.declaredMethods.singleOrNull { method ->
            method.name == "connectDescriptorWithRetry" &&
                method.parameterTypes.contentEquals(
                    arrayOf(
                        String::class.java,
                        NativeEngine::class.java,
                        Int::class.javaPrimitiveType,
                        Long::class.javaPrimitiveType,
                    ),
                )
        }

        assertTrue(overload != null, "socket connection must accept an explicitly captured NativeEngine")
        val failure = assertThrows<InvocationTargetException> {
            overload!!.invoke(SupervisorSocketUtils, "/tmp/test.sock", suppliedRuntime, 1, 0L)
        }

        assertTrue(failure.cause is IllegalStateException)
        assertEquals(1, socketCalls)
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
            io.mazewall.platform.socket.RealSocketManager
                .createUnixServer("/tmp/test_server.sock")
        }

        val expectedType = SupervisorSocketUtils.SOCK_STREAM or io.mazewall.ffi.NativeConstants.SOCK_CLOEXEC
        assertEquals(expectedType, socketTypeArg)
    }

    @Test
    fun `UnixServerSocket uses the supplied native runtime`() {
        val suppliedRuntime = MockNativeEngine(
            networking = object : MockNativeNetworking() {
                override fun socket(
                    domain: Int,
                    type: Int,
                    protocol: Int,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                    LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(123L).testAcquisition()

                override fun bind(
                    sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    addr: ManagedSegment,
                    addrlen: Int,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(0L)

                override fun listen(
                    sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    backlog: Int,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(0L)
            },
        )
        val overload = UnixServerSocket::class.java.declaredMethods.singleOrNull { method ->
            method.name == "open" &&
                method.parameterTypes.contentEquals(arrayOf(String::class.java, NativeEngine::class.java))
        }

        assertTrue(overload != null, "UNIX server creation must accept an explicitly captured NativeEngine")
        val socket = overload!!.invoke(UnixServerSocket, "/tmp/test.sock", suppliedRuntime)

        assertEquals(123, (socket as FileDescriptor<*, *, *>).value)
    }

    @Test
    fun `RealSocketManager accept calls accept4 with SOCK_CLOEXEC`() {
        var accept4Flags = 0
        val mockNetworking = object : MockNativeNetworking() {
            override fun accept4(
                sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                addr: ManagedSegment,
                addrlen: ManagedSegment,
                flags: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                accept4Flags = flags
                return LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(99L).testAcquisition()
            }
        }

        val mockEngine = object : MockNativeEngine(networking = mockNetworking) {}
        LinuxNative.setEngine(mockEngine)

        val serverFd = TestFileDescriptors
            .replace<FileDescriptorRole.UnixSocket>(10)
        val clientFd = io.mazewall.platform.socket.RealSocketManager
            .accept(serverFd)

        assertEquals(99, clientFd.value)
        assertEquals(io.mazewall.ffi.NativeConstants.SOCK_CLOEXEC, accept4Flags)
    }
}
