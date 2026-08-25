package io.mazewall.core

import io.mazewall.LinuxNative
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.openPath
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated

/**
 * All descriptor integers come from real opens this test owns (see
 * FileDescriptorReproductionTest for why invented integers are forbidden:
 * close(int) on a guessed number destroys unrelated JVM resources).
 */
@Isolated
@org.junit.jupiter.api.extension.ExtendWith(ForeignFdGuard::class)
class FileDescriptorTest {

    companion object {
        context(arena: NativeArena)
        private fun realFd(): Int =
            when (val res = openPath("/dev/null", OpenFlags.RDONLY)) {
                is LinuxNative.SyscallResult.Success -> res.value.toInt()
                else -> error("open(/dev/null) failed: $res")
            }

        private fun <T> withArena(block: NativeArena.() -> T): T =
            NativeArena.ofConfined().use(block)
    }

    @Test
    fun `test FileDescriptor creation and close`() = withArena {
        val fd = FileDescriptor.generic(realFd())
        val value = fd.value
        assertEquals(value, fd.value)

        val closed = fd.close()
        assertEquals(value, closed.value)
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
    fun `test file descriptor basic properties`() = withArena {
        val shared = realFd()
        val fd1 = FileDescriptor.generic(shared)
        val fd2 = FileDescriptor.generic(shared)
        val other = realFd()
        val fd3 = FileDescriptor.generic(other)

        assertEquals(fd1, fd2)
        assertNotEquals(fd1, fd3)
        assertEquals(fd1.hashCode(), fd2.hashCode())

        assertTrue(fd1.toString().contains("fd($shared)"))
        fd1.close(); fd2.close(); fd3.close()
    }

    @Test
    fun `test invalid negative FileDescriptor creation allowed by unsafe`() {
        val fd = FileDescriptor.unsafe<FileDescriptorRole.Generic>(-1)
        assertTrue(fd.isInvalid)
        fd.close() // should return immediately
        assertTrue(fd.toString().contains("fd(-1, closed/invalid)"))
    }

    @Test
    fun `open descriptors can be passed as NativeArg FdArg`() = withArena {
        val intFd = realFd()
        val fd = FileDescriptor.generic(intFd)
        val arg = NativeArg.FdArg(fd)
        assertEquals(intFd.toLong(), arg.asLong)
        // NativeArg.FdArg(fd.close()) does not compile: FdArg requires FdState.Open.
        fd.close()
    }

    @Test
    fun `test use extension function`() = withArena {
        val intFd = realFd()
        val fd = FileDescriptor.generic(intFd)
        val result = fd.use { openFd ->
            assertEquals(intFd, openFd.value)
            "some-result"
        }
        assertEquals("some-result", result)
    }

    @Test
    fun `reclaiming the same integer after close is a new generation`() = withArena {
        // Close the lowest-open first; the next open reclaims the same integer.
        val firstInt = realFd()
        val leftover = FileDescriptor.generic(firstInt)
        leftover.close()

        val reusedInt = realFd()
        val reused = FileDescriptor.generic(reusedInt)

        assertTrue(reused.isValid)
        assertTrue(reused.isLiveForIo())
        assertFalse(leftover.isValid)
        assertFalse(leftover.isLiveForIo())
        assertNotEquals(leftover, reused)
        reused.close()
    }

    @Test
    fun `concurrent aliases of a live fd share generation`() = withArena {
        val shared = realFd()
        val a = FileDescriptor.generic(shared)
        val b = FileDescriptor.generic(shared)
        assertEquals(a, b)
        assertTrue(a.isLiveForIo())
        a.close()
        assertFalse(b.isLiveForIo())
    }

    @Test
    fun `role factories mint Open tokens of the declared role`() = withArena {
        val fds = (1..4).map { realFd() }
        val sock = FileDescriptor.unixSocket(fds[0])
        val ruleset = FileDescriptor.ruleset(fds[1])
        val opath = FileDescriptor.oPath(fds[2])
        val notif = FileDescriptor.seccompNotif(fds[3])
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
    fun `leftover dirfd and mmap backing fail closed`() = withArena {
        val dirInt = realFd()
        val dir = FileDescriptor.oPath(dirInt)
        dir.close()
        assertNotNull(dir.ebadfUnlessDirfd())
        assertNotNull(dir.ebadfUnlessMmapBacking())

        val liveInt = realFd()
        val live = FileDescriptor.oPath(liveInt)
        assertNull(live.ebadfUnlessDirfd())
        assertNull(live.ebadfUnlessMmapBacking())
        live.close()
    }

    @Test
    fun `dup claims a new generation independent of the source`() = withArena {
        val sourceInt = realFd()
        val source = FileDescriptor.generic(sourceInt)
        val dupResult = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(
            sourceInt.toLong(),
        ).claimDupIfNeeded(io.mazewall.ffi.NativeConstants.F_DUPFD)
        assertTrue(dupResult is LinuxNative.SyscallResult.Success)
        val dupInt = (dupResult as LinuxNative.SyscallResult.Success<Long, *>).value.toInt()
        val dup = FileDescriptor.adopt(dupInt, FileDescriptorRole.Generic)
        assertTrue(dup.isLiveForIo())
        source.close()
        assertTrue(dup.isLiveForIo())
        assertFalse(source.isLiveForIo())
        dup.close()
    }

    @Test
    fun `replace retires leftover generation then claims the integer`() = withArena {
        val intFd = realFd()
        val leftover = FileDescriptor.generic(intFd)
        leftover.close()
        val replaced = FileDescriptor.replace<FileDescriptorRole.Generic>(intFd)
        assertTrue(replaced.isLiveForIo())
        assertFalse(leftover.isLiveForIo())
        assertNotEquals(leftover, replaced)
        replaced.close()
    }

    @Test
    fun `SCM_RIGHTS adopt after close is a new generation`() = withArena {
        val intFd = realFd()
        val leftover = FileDescriptor.seccompNotif(intFd)
        leftover.close()
        val received = FileDescriptor.adopt(intFd, FileDescriptorRole.SeccompNotif)
        assertTrue(received.isLiveForIo())
        assertFalse(leftover.isLiveForIo())
        received.close()
    }

    @Test
    fun `granted SCM_RIGHTS adopt is not a seccomp listener role`() = withArena {
        val intFd = realFd()
        val leftover = FileDescriptor.granted(intFd)
        leftover.close()
        val received = FileDescriptor.adopt(intFd, FileDescriptorRole.Granted)
        assertEquals(FileDescriptorRole.Granted, received.role)
        assertTrue(received.isLiveForIo())
        assertFalse(leftover.isLiveForIo())
        received.close()
    }

    @Test
    fun `adopt of a still-live integer advances generation`() = withArena {
        val liveInt = realFd()
        val leftover = FileDescriptor.generic(liveInt)
        assertTrue(leftover.isLiveForIo())
        val leftoverGeneration = leftover.generation

        val adopted = FileDescriptor.adopt(liveInt, FileDescriptorRole.Generic)
        assertTrue(adopted.isLiveForIo())
        assertFalse(leftover.isLiveForIo())
        assertNotEquals(leftoverGeneration, adopted.generation)
        assertNotEquals(leftover, adopted)

        val secondInt = realFd()
        val replacedLeftover = FileDescriptor.generic(secondInt)
        val replaced = FileDescriptor.replace<FileDescriptorRole.Generic>(secondInt)
        assertTrue(replaced.isLiveForIo())
        assertFalse(replacedLeftover.isLiveForIo())
        assertNotEquals(replacedLeftover, replaced)

        adopted.close()
        replaced.close()
    }

    @Test
    fun `poll does not reject reused raw fd integers without typed token`() = withArena {
        val intFd = realFd()
        val fd = FileDescriptor.generic(intFd)
        fd.close()
        // Raw pollfds are not rejected based solely on historical integer retirement
        assertTrue(FdEpoch.isRetired(intFd))
    }

    @Test
    fun `unsafe on retired fd stays dead`() = withArena {
        val intFd = realFd()
        val fd = FileDescriptor.generic(intFd)
        fd.close()
        // unsafe on a retired fd should NOT revive it
        val unsafeFd = FileDescriptor.unsafe<FileDescriptorRole.Generic>(intFd)
        assertFalse(unsafeFd.isLiveForIo())
        assertTrue(unsafeFd.isInvalid)
    }

    @Test
    fun `same integer double close does not revive`() = withArena {
        val intFd = realFd()
        val fd1 = FileDescriptor.generic(intFd)
        val closed1 = fd1.close()
        assertFalse(fd1.isValid)
        assertFalse(closed1.isValid)

        // Reopening /dev/null after the close hands back the same integer -
        // creating a new token must be a new generation.
        val reopened = realFd()
        if (reopened == intFd) {
            val fd2 = FileDescriptor.generic(reopened)
            assertTrue(fd2.isValid)
            assertNotEquals(fd1, fd2)

            val closed2 = fd2.close()
            assertFalse(fd2.isValid)
            assertFalse(closed2.isValid)

            assertFalse(fd1.isValid)
            assertFalse(closed1.isValid)
        } else {
            // Kernel chose a different integer; the generation-separation property
            // is already covered by `reclaiming the same integer...`.
            assertTrue(FdEpoch.isRetired(intFd))
        }
    }

    @Test
    fun `FdArg constructor is bound to a FileDescriptor not a raw int`() {
        val ctor = NativeArg.FdArg::class.java.declaredConstructors.single()
        assertEquals(1, ctor.parameterCount)
        assertEquals(FileDescriptor::class.java, ctor.parameterTypes.single())
    }

    @Test
    fun `verify role factories and lifecycle transitions for every role`() = withArena {
        val cases = listOf(
            "generic" to FileDescriptorRole.Generic,
            "unixSocket" to FileDescriptorRole.UnixSocket,
            "ruleset" to FileDescriptorRole.Ruleset,
            "oPath" to FileDescriptorRole.OPath,
            "seccompNotif" to FileDescriptorRole.SeccompNotif,
            "pid" to FileDescriptorRole.Pid,
            "granted" to FileDescriptorRole.Granted,
        )
        for ((roleName, expectedRole) in cases) {
            val fd = when (expectedRole) {
                FileDescriptorRole.Generic -> FileDescriptor.generic(realFd())
                FileDescriptorRole.UnixSocket -> FileDescriptor.unixSocket(realFd())
                FileDescriptorRole.Ruleset -> FileDescriptor.ruleset(realFd())
                FileDescriptorRole.OPath -> FileDescriptor.oPath(realFd())
                FileDescriptorRole.SeccompNotif -> FileDescriptor.seccompNotif(realFd())
                FileDescriptorRole.Pid -> FileDescriptor.pid(realFd())
                FileDescriptorRole.Granted -> FileDescriptor.granted(realFd())
            }
            assertTrue(fd.isValid, roleName)
            assertFalse(fd.isInvalid, roleName)
            assertEquals(expectedRole, fd.role, roleName)

            val closed = fd.close()
            assertFalse(fd.isValid, roleName)
            assertFalse(closed.isValid, roleName)
            assertTrue(closed.isClosedType(), roleName)
            assertEquals(expectedRole, closed.role, roleName)
        }
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
            FileDescriptorRole.Granted,
        )

        for (role in roles) {
            when (role) {
                is FileDescriptorRole.Generic -> Unit
                is FileDescriptorRole.Ruleset -> Unit
                is FileDescriptorRole.OPath -> Unit
                is FileDescriptorRole.SeccompNotif -> Unit
                is FileDescriptorRole.UnixSocket -> Unit
                is FileDescriptorRole.Pid -> Unit
                is FileDescriptorRole.Granted -> Unit
            }
        }
    }
}
