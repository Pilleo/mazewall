package io.mazewall.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FileDescriptorTest {

    @Test
    fun `test FileDescriptor creation and close`() {
        val fd = FileDescriptor.unsafe<FileDescriptorRole.Generic>(100)
        assertEquals(100, fd.value)

        val closed = fd.close()
        assertEquals(100, closed.value)
        @Suppress("USELESS_IS_CHECK")
        assertTrue(closed is FileDescriptor<*, FdState.Closed>)
        assertTrue(closed.isClosedType())
        assertFalse(closed.isValid)

        // COMPILE-TIME SAFETY DEMONSTRATION:
        // The following lines will not compile because 'closed' is typed as FdState.Closed
        // and cannot be closed again or used:
        // closed.close() // Compile error!
        // closed.use { ... } // Compile error!
        // FileDescriptor.INVALID.close() // Compile error!
    }

    @Test
    fun `test file descriptor basic properties`() {
        val fd1 = FileDescriptor.unsafe<FileDescriptorRole.Generic>(10)
        val fd2 = FileDescriptor.unsafe<FileDescriptorRole.Generic>(10)
        val fd3 = FileDescriptor.unsafe<FileDescriptorRole.Generic>(11)

        assertEquals(fd1, fd2)
        assertNotEquals(fd1, fd3)
        assertEquals(fd1.hashCode(), fd2.hashCode())

        assertTrue(fd1.toString().contains("fd(10)"))
    }

    @Test
    fun `test invalid negative FileDescriptor creation allowed by unsafe`() {
        val fd = FileDescriptor.unsafe<FileDescriptorRole.Generic>(-1)
        assertTrue(fd.isInvalid)
        fd.close() // should return immediately
        assertTrue(fd.toString().contains("fd(-1, closed/invalid)"))
    }

    @Test
    fun `test use extension function`() {
        val fd = FileDescriptor.unsafe<FileDescriptorRole.Generic>(50)
        val result = fd.use { openFd ->
            assertEquals(50, openFd.value)
            "some-result"
        }
        assertEquals("some-result", result)
    }
}
