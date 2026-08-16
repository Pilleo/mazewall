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

        // The original Open-typed token must also observe the close so a stale
        // handle cannot pass isValid and be reused as if it were still live.
        assertFalse(fd.isValid)
        assertTrue(fd.isClosedType())

        // COMPILE-TIME SAFETY DEMONSTRATION:
        // The following lines will not compile because 'closed' is typed as FdState.Closed
        // and cannot be closed again or used:
        // closed.close() // Compile error!
        // closed.use { ... } // Compile error!
        // FileDescriptor.INVALID.close() // Compile error!
        // NativeArg.FdArg(closed) // Compile error!
        // LinuxNative.fileSystem.close(closed) // Compile error!
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
    fun `open descriptors can be passed as NativeArg FdArg`() {
        val fd = FileDescriptor.unsafe<FileDescriptorRole.Generic>(7)
        val arg = NativeArg.FdArg(fd)
        assertEquals(7L, arg.asLong)
        // NativeArg.FdArg(fd.close()) does not compile: FdArg requires FdState.Open.
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

    @Test
    fun `reclaiming the same integer after close is a new generation`() {
        val leftover = FileDescriptor.generic(80)
        leftover.close()
        val reused = FileDescriptor.generic(80)

        assertTrue(reused.isValid)
        assertTrue(reused.isLiveForIo())
        assertFalse(leftover.isValid)
        assertFalse(leftover.isLiveForIo())
        assertNotEquals(leftover, reused)
    }

    @Test
    fun `concurrent aliases of a live fd share generation`() {
        val a = FileDescriptor.generic(81)
        val b = FileDescriptor.generic(81)
        assertEquals(a, b)
        assertTrue(a.isLiveForIo())
        a.close()
        assertFalse(b.isLiveForIo())
    }

    @Test
    fun `role factories mint Open tokens of the declared role`() {
        val sock = FileDescriptor.unixSocket(82)
        val ruleset = FileDescriptor.ruleset(83)
        val opath = FileDescriptor.oPath(84)
        val notif = FileDescriptor.seccompNotif(85)
        assertTrue(sock.isLiveForIo())
        assertTrue(ruleset.isLiveForIo())
        assertTrue(opath.isLiveForIo())
        assertTrue(notif.isLiveForIo())
        sock.close()
        ruleset.close()
        opath.close()
        notif.close()
    }

    @Test
    fun `FileDescriptor exposes no public integer constructor`() {
        val publicIntCtor =
            FileDescriptor::class.java.declaredConstructors.filter { ctor ->
                java.lang.reflect.Modifier.isPublic(ctor.modifiers) &&
                    ctor.parameterTypes.any { it == Int::class.javaPrimitiveType || it == Int::class.java }
            }
        assertTrue(publicIntCtor.isEmpty(), "public FileDescriptor(int) would mint fake Closed tokens")
    }

    @Test
    fun `FdArg constructor is bound to a FileDescriptor not a raw int`() {
        val ctor = NativeArg.FdArg::class.java.declaredConstructors.single()
        assertEquals(1, ctor.parameterCount)
        assertEquals(FileDescriptor::class.java, ctor.parameterTypes.single())
    }
}
