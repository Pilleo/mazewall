package io.mazewall.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FileDescriptorTest {

    @Test
    fun `test FileDescriptor creation and close`() {
        val fd = FileDescriptor.unsafe<FileDescriptorRole.Generic>(100)
        assertEquals(100, fd.value)

        val closed = fd.closeFd()
        assertEquals(100, closed.value)
        assertTrue(closed is FileDescriptor<*, FdState.Closed>)

        fd.close() // Verify AutoCloseable logic doesn't crash
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
}
