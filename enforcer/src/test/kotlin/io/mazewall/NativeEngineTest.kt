package io.mazewall

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import io.mazewall.ffi.IoctlCommand
import io.mazewall.ffi.IoctlPayload
import io.mazewall.ffi.typed
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole

class NativeEngineTest {
    companion object {
        @JvmStatic
        fun ioctlCommands() = listOf(
            IoctlCommand.SECCOMP_IOCTL_NOTIF_RECV,
            IoctlCommand.SECCOMP_IOCTL_NOTIF_SEND,
            IoctlCommand.SECCOMP_IOCTL_NOTIF_ADDFD
        )
    }

    @AfterEach
    fun tearDown() {
        // Always reset to real engine after each test to avoid polluting other tests
        LinuxNative.resetToDefault()
    }

    @ParameterizedTest
    @MethodSource("ioctlCommands")
    fun `strongly typed ioctl signature delegates correctly and integrates with MockNativeEngine`(command: IoctlCommand<*, *>) {
        val mock = MockNativeEngine()
        var lastCommandCode = -1L
        mock.onIoctl = { _, request, _ ->
            lastCommandCode = request
            LinuxNative.SyscallResult.Success(99L)
        }

        LinuxNative.setEngine(mock)

        val dummyFd = FileDescriptor.unsafe<FileDescriptorRole.Generic>(100)
        val dummySegment = io.mazewall.ffi.memory.ManagedSegment.NULL

        @Suppress("UNCHECKED_CAST")
        val result = LinuxNative.raw.ioctl(dummyFd, command as IoctlCommand<Any, Any>, dummySegment.typed<IoctlPayload.SeccompNotif>())
        assertEquals(99L, result.getOrThrow("test"))
        assertEquals(command.code, lastCommandCode)
    }

    @Test
    fun `LinuxNative delegates to injected engine`() {
        val mock = MockNativeEngine()
        mock.process.prctlResult = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(42)

        LinuxNative.setEngine(mock)

        val result = LinuxNative.process.prctl(io.mazewall.core.PrctlCommand.SetNoNewPrivs(true))
        assertEquals(42L, result.getOrThrow("test"))
    }

    @Test
    fun `fault injection works for errno`() {
        val mock = MockNativeEngine()
        mock.syscallResult = LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(13, -1) // EACCES

        LinuxNative.setEngine(mock)

        val result = LinuxNative.raw.syscall(1L)
        assertEquals(13, (result as LinuxNative.SyscallResult.Error).errno)
    }

    @Test
    fun `contracts allow local val initialization inside nativeScope`() {
        val initializedInScope: String
        io.mazewall.ffi.memory.nativeScope {
            initializedInScope = "hello"
        }
        assertEquals("hello", initializedInScope)
    }

    @Test
    fun `RealNativeHelper toLong converts standard and custom value classes correctly`() {
        val helper = io.mazewall.ffi.internal.RealNativeHelper
        assertEquals(10L, helper.toLong(10L))
        assertEquals(10L, helper.toLong(10))
        assertEquals(10L, helper.toLong(10.toShort()))
        assertEquals(10L, helper.toLong(10.toByte()))
        assertEquals(0L, helper.toLong(null))

        assertEquals(123L, helper.toLong(io.mazewall.core.OpenFlags(123)))
        assertEquals(456L, helper.toLong(io.mazewall.core.MmapProt(456)))
        assertEquals(789L, helper.toLong(io.mazewall.core.MmapFlags(789)))
        assertEquals(9999L, helper.toLong(io.mazewall.core.CloneFlags(9999L)))

        assertEquals(12L, helper.toLong(io.mazewall.core.Pid(12)))
        assertEquals(34L, helper.toLong(io.mazewall.core.Tid(34)))
        assertEquals(56L, helper.toLong(io.mazewall.core.Uid(56)))
        assertEquals(1001L, helper.toLong(io.mazewall.core.MemoryAddress(1001L)))
        assertEquals(99L, helper.toLong(FileDescriptor.unsafe<io.mazewall.core.FileDescriptorRole.Generic>(99)))
    }

    @Test
    fun `SyscallResult isSuccess and isFailure smart casts correctly`() {
        val successResult: LinuxNative.SyscallResult<String, LinuxNative.SyscallHandledState.Unhandled> =
            LinuxNative.SyscallResult.Success("test-value")

        if (successResult.isSuccess()) {
            // Smart cast allows accessing .value directly on successResult
            assertEquals("test-value", successResult.value)
        } else {
            org.junit.jupiter.api.Assertions.fail("Expected success")
        }

        val failureResult: LinuxNative.SyscallResult<String, LinuxNative.SyscallHandledState.Unhandled> =
            LinuxNative.SyscallResult.Error(5, -1L)

        if (failureResult.isFailure()) {
            // Smart cast allows accessing .errno directly on failureResult
            assertEquals(5, failureResult.errno)
        } else {
            org.junit.jupiter.api.Assertions.fail("Expected failure")
        }
    }
}
