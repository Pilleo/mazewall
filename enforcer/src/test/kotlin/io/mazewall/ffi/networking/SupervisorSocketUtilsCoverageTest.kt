package io.mazewall.ffi.networking

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.Pid
import io.mazewall.ffi.NativeConstants
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

class SupervisorSocketUtilsCoverageTest {

    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
    }

    @Test
    fun `test sendDescriptor success`() {
        val mock = MockNativeEngine()
        LinuxNative.setEngine(mock)

        mock.networking.sendmsgResult = LinuxNative.SyscallResult.Success(1L)

        val result = SupervisorSocketUtils.sendDescriptor(10, 20)
        assertTrue(result)
    }

    @Test
    fun `test sendDescriptor failure`() {
        val mock = MockNativeEngine()
        LinuxNative.setEngine(mock)

        mock.networking.sendmsgResult = LinuxNative.SyscallResult.Error(NativeConstants.EPERM, -1L)

        val result = SupervisorSocketUtils.sendDescriptor(10, 20)
        assertFalse(result)
    }

    @Test
    fun `test sendDescriptor EINTR retry`() {
        val mock = MockNativeEngine()
        LinuxNative.setEngine(mock)

        var calls = 0
        mock.networking.onSendmsg = { _, _, _ ->
            calls++
            if (calls == 1) LinuxNative.SyscallResult.Error(NativeConstants.EINTR, -1L)
            else LinuxNative.SyscallResult.Success(1L)
        }

        val result = SupervisorSocketUtils.sendDescriptor(10, 20)
        assertTrue(result)
        assertEquals(2, calls)
    }

    @Test
    fun `test recvDescriptor success`() {
        val mock = MockNativeEngine()
        LinuxNative.setEngine(mock)

        mock.networking.onRecvmsg = { _, msg, _ ->
            // msg is struct msghdr
            // msg_control is at offset 16 on 64-bit
            val controlAddr = msg.get(ValueLayout.ADDRESS, 16).reinterpret(24L)
            // msg_controllen is at offset 24
            msg.set(ValueLayout.JAVA_LONG, 24, 24L)

            // Fill controlBuf with cmsghdr
            // cmsg_len is 20L (offset 0)
            controlAddr.set(ValueLayout.JAVA_LONG, 0, 20L)
            // cmsg_level is 1 (SOL_SOCKET) (offset 8)
            controlAddr.set(ValueLayout.JAVA_INT, 8, 1)
            // cmsg_type is 1 (SCM_RIGHTS) (offset 12)
            controlAddr.set(ValueLayout.JAVA_INT, 12, 1)
            // data fd is at offset 16
            controlAddr.set(ValueLayout.JAVA_INT, 16, 42)

            LinuxNative.SyscallResult.Success(1L)
        }

        val fd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10)
        val result = SupervisorSocketUtils.recvDescriptor(fd)
        assertNotNull(result)
        assertEquals(42, result!!.value)
    }

    @Test
    fun `test recvDescriptor EOF`() {
        val mock = MockNativeEngine()
        LinuxNative.setEngine(mock)

        mock.networking.recvmsgResult = LinuxNative.SyscallResult.Success(0L)

        val fd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10)
        val result = SupervisorSocketUtils.recvDescriptor(fd)
        assertNull(result)
    }

    @Test
    fun `test recvDescriptor error`() {
        val mock = MockNativeEngine()
        LinuxNative.setEngine(mock)

        mock.networking.recvmsgResult = LinuxNative.SyscallResult.Error(NativeConstants.EPERM, -1L)

        val fd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10)
        val result = SupervisorSocketUtils.recvDescriptor(fd)
        assertNull(result)
    }
}
