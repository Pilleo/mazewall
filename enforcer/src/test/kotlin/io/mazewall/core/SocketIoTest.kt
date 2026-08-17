package io.mazewall.core

import io.mazewall.LinuxNative
import io.mazewall.MockNativeMemory
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SocketIoTest {
    @Test
    fun `writeFully retries short writes`() {
        NativeArena.ofConfined().use { arena ->
            val buf = with(arena) { allocate(8) }
            var calls = 0
            val memory =
                object : MockNativeMemory() {
                    override fun write(
                        fd: FileDescriptor<*, FdState.Open>,
                        buf: io.mazewall.ffi.memory.ManagedSegment,
                        count: Long,
                    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                        calls++
                        val n = minOf(3L, count)
                        return LinuxNative.SyscallResult.Success(n)
                    }
                }
            val fd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(7)
            val res = SocketIo.writeFully(memory, fd, buf, 8)
            assertTrue(res is LinuxNative.SyscallResult.Success)
            assertEquals(8L, (res as LinuxNative.SyscallResult.Success).value)
            assertEquals(3, calls)
        }
    }

    @Test
    fun `readFully fails closed on zero-length read`() {
        NativeArena.ofConfined().use { arena ->
            val buf = with(arena) { allocate(4) }
            val memory =
                object : MockNativeMemory() {
                    override fun read(
                        fd: FileDescriptor<*, FdState.Open>,
                        buf: io.mazewall.ffi.memory.ManagedSegment,
                        count: Long,
                    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                        LinuxNative.SyscallResult.Success(0L)
                }
            val fd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(7)
            val res = SocketIo.readFully(memory, fd, buf, 4)
            assertTrue(res is LinuxNative.SyscallResult.Error)
            assertEquals(NativeConstants.EIO, (res as LinuxNative.SyscallResult.Error).errno)
        }
    }
}
