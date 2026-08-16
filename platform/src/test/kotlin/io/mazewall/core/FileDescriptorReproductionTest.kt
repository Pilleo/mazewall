package io.mazewall.core

import io.mazewall.LinuxNative
import org.junit.jupiter.api.Test
import kotlin.test.*

class FileDescriptorReproductionTest {

    @Test
    fun `file descriptor is strictly immutable and returns closed type`() {
        val fd = FileDescriptor.unsafe<FileDescriptorRole.Generic>(10)
        assertEquals(10, fd.value)

        // FileDescriptor should not implement AutoCloseable directly to prevent use-after-close errors
        @Suppress("CAST_NEVER_SUCCEEDS", "USELESS_CAST")
        val isAutoCloseable = fd as? AutoCloseable
        assertNull(isAutoCloseable, "FileDescriptor should not directly be AutoCloseable")

        val closedFd = fd.close()
        assertEquals(10, closedFd.value)
        assertFalse(fd.isValid)
        assertFalse(closedFd.isValid)

        // The Closed token cannot be passed to Open-only APIs (close, use, FdArg, I/O).
        @Suppress("USELESS_CAST")
        assertTrue(closedFd is FileDescriptor<*, FdState.Closed>)
    }

    @Test
    fun `leftover Open token cannot reach the kernel after close or reuse`() {
        val leftover = FileDescriptor.generic(90)
        leftover.close()

        val denied = leftover.ebadfUnlessLive()
        assertNotNull(denied)
        assertTrue(denied is LinuxNative.SyscallResult.Error)
        assertEquals(io.mazewall.ffi.NativeConstants.EBADF, (denied as LinuxNative.SyscallResult.Error).errno)

        val reused = FileDescriptor.generic(90)
        assertTrue(reused.isLiveForIo())
        assertFalse(leftover.isLiveForIo())
        assertNull(reused.ebadfUnlessLive())
        reused.close()
    }
}
