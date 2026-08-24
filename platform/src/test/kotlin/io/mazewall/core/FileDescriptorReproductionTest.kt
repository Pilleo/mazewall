package io.mazewall.core

import io.mazewall.LinuxNative
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.openPath
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Every descriptor integer used here comes from a real open the test owns.
 * Minting tokens around INVENTED integers performs real close(int) syscalls in
 * the shared test JVM and has destroyed unrelated resources (e.g. the lazily
 * opened /dev/urandom fd), surfacing as EBADF in unrelated SecureRandom users.
 */
@org.junit.jupiter.api.extension.ExtendWith(ForeignFdGuard::class)
class FileDescriptorReproductionTest {

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
    fun `file descriptor is strictly immutable and returns closed type`() = withArena {
        val fd = FileDescriptor.unsafe<FileDescriptorRole.Generic>(realFd())
        val value = fd.value

        @Suppress("CAST_NEVER_SUCCEEDS", "USELESS_CAST")
        val isAutoCloseable = fd as? AutoCloseable
        assertNull(isAutoCloseable, "FileDescriptor should not directly be AutoCloseable")

        val closedFd = fd.close()
        assertEquals(value, closedFd.value)
        assertFalse(fd.isValid)
        assertFalse(closedFd.isValid)

        @Suppress("USELESS_CAST")
        assertTrue(closedFd is FileDescriptor<*, FdState.Closed>)
    }

    @Test
    fun `leftover Open token cannot reach the kernel after close or reuse`() = withArena {
        val first = realFd()
        val leftover = FileDescriptor.generic(first)
        leftover.close()

        val denied = leftover.ebadfUnlessLive()
        assertNotNull(denied)
        assertTrue(denied is LinuxNative.SyscallResult.Error)
        assertEquals(io.mazewall.ffi.NativeConstants.EBADF, (denied as LinuxNative.SyscallResult.Error).errno)

        // The kernel hands back the just-closed lowest integer; adopting it proves
        // generation separation between the leftover token and the new owner.
        val reusedInt = realFd()
        val reused = FileDescriptor.generic(reusedInt)
        assertTrue(reused.isLiveForIo())
        assertFalse(leftover.isLiveForIo())
        assertNull(reused.ebadfUnlessLive())
        reused.close()
    }
}
