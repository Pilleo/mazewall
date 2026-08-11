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

        // Even after closing, the original 'fd' reference still exists and its 'value' is unchanged
        // but it's technically invalid at the OS level. The 'Closed' type provides compile-time safety.
        @Suppress("USELESS_CAST")
        assertTrue(closedFd is FileDescriptor<*, FdState.Closed>)
    }
}
