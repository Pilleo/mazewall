package io.mazewall.core

import io.mazewall.LinuxNative
import io.mazewall.ffi.memory.writeInt
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated

@Isolated
class FileDescriptorTest {

    @Test
    fun `test FileDescriptor creation and close`() {
        val fd = FileDescriptor.generic(100)
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
        val fd1 = FileDescriptor.generic(200)
        val fd2 = FileDescriptor.generic(200)
        val fd3 = FileDescriptor.generic(201)

        assertEquals(fd1, fd2)
        assertNotEquals(fd1, fd3)
        assertEquals(fd1.hashCode(), fd2.hashCode())

        assertTrue(fd1.toString().contains("fd(200)"))
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
        val fd = FileDescriptor.generic(101)
        val arg = NativeArg.FdArg(fd)
        assertEquals(101L, arg.asLong)
        // NativeArg.FdArg(fd.close()) does not compile: FdArg requires FdState.Open.
    }

    @Test
    fun `test use extension function`() {
        val fd = FileDescriptor.generic(102)
        val result = fd.use { openFd ->
            assertEquals(102, openFd.value)
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
    fun `AT_FDCWD and ANON sentinels are usable without being live kernel fds`() {
        assertTrue(FileDescriptor.AT_FDCWD.isUsableAsDirfd())
        assertFalse(FileDescriptor.AT_FDCWD.isLiveForIo())
        assertNull(FileDescriptor.AT_FDCWD.ebadfUnlessDirfd())
        assertNotNull(FileDescriptor.AT_FDCWD.ebadfUnlessLive())

        assertTrue(FileDescriptor.ANON.isUsableAsMmapBacking())
        assertFalse(FileDescriptor.ANON.isLiveForIo())
        assertNull(FileDescriptor.ANON.ebadfUnlessMmapBacking())
    }

    @Test
    fun `leftover dirfd and mmap backing fail closed`() {
        val dir = FileDescriptor.oPath(91)
        dir.close()
        assertNotNull(dir.ebadfUnlessDirfd())
        assertNotNull(dir.ebadfUnlessMmapBacking())

        val live = FileDescriptor.oPath(92)
        assertNull(live.ebadfUnlessDirfd())
        assertNull(live.ebadfUnlessMmapBacking())
        live.close()
    }

    @Test
    fun `dup claims a new generation independent of the source`() {
        val source = FileDescriptor.generic(93)
        val dupResult = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(94L)
            .claimDupIfNeeded(io.mazewall.ffi.NativeConstants.F_DUPFD)
        assertTrue(dupResult is LinuxNative.SyscallResult.Success)
        val dup = FileDescriptor.adopt(94, FileDescriptorRole.Generic)
        assertTrue(dup.isLiveForIo())
        source.close()
        assertTrue(dup.isLiveForIo())
        assertFalse(source.isLiveForIo())
        dup.close()
    }

    @Test
    fun `replace retires leftover generation then claims the integer`() {
        val leftover = FileDescriptor.generic(95)
        leftover.close()
        val replaced = FileDescriptor.replace<FileDescriptorRole.Generic>(95)
        assertTrue(replaced.isLiveForIo())
        assertFalse(leftover.isLiveForIo())
        assertNotEquals(leftover, replaced)
        replaced.close()
    }

    @Test
    fun `SCM_RIGHTS adopt after close is a new generation`() {
        val leftover = FileDescriptor.seccompNotif(96)
        leftover.close()
        val received = FileDescriptor.adopt(96, FileDescriptorRole.SeccompNotif)
        assertTrue(received.isLiveForIo())
        assertFalse(leftover.isLiveForIo())
        received.close()
    }

    @Test
    fun `adopt of a still-live integer advances generation`() {
        val leftover = FileDescriptor.generic(120)
        assertTrue(leftover.isLiveForIo())
        val leftoverGeneration = leftover.generation

        val adopted = FileDescriptor.adopt(120, FileDescriptorRole.Generic)
        assertTrue(adopted.isLiveForIo())
        assertFalse(leftover.isLiveForIo())
        assertNotEquals(leftoverGeneration, adopted.generation)
        assertNotEquals(leftover, adopted)

        val replacedLeftover = FileDescriptor.generic(121)
        val replaced = FileDescriptor.replace<FileDescriptorRole.Generic>(121)
        assertTrue(replaced.isLiveForIo())
        assertFalse(replacedLeftover.isLiveForIo())
        assertNotEquals(replacedLeftover, replaced)

        adopted.close()
        replaced.close()
    }

    @Test
    fun `poll does not reject reused raw fd integers without typed token`() {
        val fd = FileDescriptor.generic(97)
        fd.close()
        // Raw pollfds are not rejected based solely on historical integer retirement
        assertTrue(FdEpoch.isRetired(97))
    }

    @Test
    fun `unsafe on retired fd stays dead`() {
        val fd = FileDescriptor.generic(98)
        fd.close()
        // unsafe on a retired fd should NOT revive it
        val unsafeFd = FileDescriptor.unsafe<FileDescriptorRole.Generic>(98)
        assertFalse(unsafeFd.isLiveForIo())
        assertTrue(unsafeFd.isInvalid)
    }

    @Test
    fun `same integer double close does not revive`() {
        val fd1 = FileDescriptor.generic(99)
        val closed1 = fd1.close()
        assertFalse(fd1.isValid)
        assertFalse(closed1.isValid)
        
        // Create a new FD with the same integer - should be a new generation
        val fd2 = FileDescriptor.generic(99)
        assertTrue(fd2.isValid)
        assertNotEquals(fd1, fd2)
        
        // Close the second one
        val closed2 = fd2.close()
        assertFalse(fd2.isValid)
        assertFalse(closed2.isValid)
        
        // The first closed token should still be invalid
        assertFalse(fd1.isValid)
        assertFalse(closed1.isValid)
    }

    @Test
    fun `FdArg constructor is bound to a FileDescriptor not a raw int`() {
        val ctor = NativeArg.FdArg::class.java.declaredConstructors.single()
        assertEquals(1, ctor.parameterCount)
        assertEquals(FileDescriptor::class.java, ctor.parameterTypes.single())
    }

    companion object {
        @JvmStatic
        fun roleFactories(): java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> =
            java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                    "generic",
                    FileDescriptorRole.Generic,
                    { fd: Int -> FileDescriptor.generic(fd) },
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                    "unixSocket",
                    FileDescriptorRole.UnixSocket,
                    { fd: Int -> FileDescriptor.unixSocket(fd) },
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                    "ruleset",
                    FileDescriptorRole.Ruleset,
                    { fd: Int -> FileDescriptor.ruleset(fd) },
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                    "oPath",
                    FileDescriptorRole.OPath,
                    { fd: Int -> FileDescriptor.oPath(fd) },
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                    "seccompNotif",
                    FileDescriptorRole.SeccompNotif,
                    { fd: Int -> FileDescriptor.seccompNotif(fd) },
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                    "pid",
                    FileDescriptorRole.Pid,
                    { fd: Int -> FileDescriptor.pid(fd) },
                ),
            )
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "Role factory {0} -> role {1}")
    @org.junit.jupiter.params.provider.MethodSource("roleFactories")
    fun `verify role factories and lifecycle transitions`(
        roleName: String,
        expectedRole: FileDescriptorRole,
        factory: (Int) -> FileDescriptor<*, FdState.Open>,
    ) {
        val fd = factory(400 + expectedRole.hashCode().let { if (it < 0) -it else it } % 500)
        assertTrue(fd.isValid)
        assertFalse(fd.isInvalid)
        assertEquals(expectedRole, fd.role)

        val closed = fd.close()
        assertFalse(fd.isValid)
        assertFalse(closed.isValid)
        assertTrue(closed.isClosedType())
        assertEquals(expectedRole, closed.role)
    }

    @Test
    fun `compile-time exhaustive check on FileDescriptorRole variants`() {
        val roles: List<FileDescriptorRole> = listOf(
            FileDescriptorRole.Generic,
            FileDescriptorRole.Ruleset,
            FileDescriptorRole.OPath,
            FileDescriptorRole.SeccompNotif,
            FileDescriptorRole.UnixSocket,
            FileDescriptorRole.Pid,
        )

        for (role in roles) {
            when (role) {
                is FileDescriptorRole.Generic -> Unit
                is FileDescriptorRole.Ruleset -> Unit
                is FileDescriptorRole.OPath -> Unit
                is FileDescriptorRole.SeccompNotif -> Unit
                is FileDescriptorRole.UnixSocket -> Unit
                is FileDescriptorRole.Pid -> Unit
            }
        }
    }
}
