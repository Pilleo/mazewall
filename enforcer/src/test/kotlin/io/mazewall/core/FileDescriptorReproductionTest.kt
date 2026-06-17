package io.mazewall.core

import io.mazewall.LinuxNative
import org.junit.jupiter.api.Test
import java.lang.foreign.Arena
import kotlin.test.*

class FileDescriptorReproductionTest {

    @Test
    fun `file descriptor is strictly immutable and returns closed type`() {
        val fd = FileDescriptor.unsafe<FileDescriptorRole.Generic>(10)
        assertEquals(10, fd.value)
        val isAutoCloseable = fd as? AutoCloseable
        assertNotNull(isAutoCloseable, "FileDescriptor should be AutoCloseable")

        val closedFd = fd.closeFd()
        assertEquals(10, closedFd.value)

        // Even after closing, the original 'fd' reference still exists and its 'value' is unchanged
        // but it's technically invalid at the OS level. The 'Closed' type provides compile-time safety.
        assertTrue(closedFd is FileDescriptor<*, FdState.Closed>)
    }
}
