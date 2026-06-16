package io.mazewall.core

import io.mazewall.LinuxNative
import org.junit.jupiter.api.Test
import java.lang.foreign.Arena
import kotlin.test.*

class FileDescriptorReproductionTest {

    @Test
    fun `file descriptor is a simple value class without scope validation`() {
        val fd = io.mazewall.LinuxNative.FileDescriptor(10)
        assertEquals(10, fd.value)
        val isAutoCloseable = fd as? AutoCloseable
        assertNotNull(isAutoCloseable, "FileDescriptor should be AutoCloseable now")

        fd.close()
        assertFalse(fd.isValid)
    }
}
