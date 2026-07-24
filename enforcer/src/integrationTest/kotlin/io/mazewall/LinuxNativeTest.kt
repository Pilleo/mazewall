package io.mazewall

import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.*
import io.mazewall.seccomp.BpfInstruction
import io.mazewall.core.NativeArg
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinuxNativeTest : BaseIntegrationTest() {
    @Test
    fun testPrctlGetSeccomp() {
        val result =
        LinuxNative.process.prctl(
            io.mazewall.core.PrctlCommand.GetSeccomp
        )

        // Usually returns 0 or 2, unless error
        assertTrue(result is LinuxNative.SyscallResult.Success && result.value >= 0)
    }

    @Test
    fun testBpfInstructionJmpBoundsValidation() {
        assertFailsWith<IllegalArgumentException> {
            BpfInstruction.Jmp(0, 256.toShort(), 0.toShort(), 0)
        }
        assertFailsWith<IllegalArgumentException> {
            BpfInstruction.Jmp(0, (-1).toShort(), 0.toShort(), 0)
        }
        assertFailsWith<IllegalArgumentException> {
            BpfInstruction.Jmp(0, 0.toShort(), 256.toShort(), 0)
        }
        // Valid bounds should not throw
        BpfInstruction.Jmp(0, 0.toShort(), 255.toShort(), 0)
    }

    @Test
    fun testBasicSyscalls() = nativeScope {
        // test open/close/read/write on a temp file
        val tempFile =
            java.nio.file.Files
                .createTempFile("native-test", ".txt")
        val path = allocateFrom(tempFile.toString())

        val openResult =
        LinuxNative.fileSystem.open(path, 0) // O_RDONLY

        val fd = openResult.getFdOrThrow("open")

        val buffer = allocate(1024)
        val readResult =
        LinuxNative.memory.read(fd, buffer, 1024)

        assertTrue(readResult is LinuxNative.SyscallResult.Success)

        val closeResult = LinuxNative.fileSystem.close(fd)
        assertTrue(closeResult is LinuxNative.SyscallResult.Success)

        tempFile.toFile().delete()
    }

    @Test
    fun testLayoutAccessors() {
        Layouts.ERRNO
        Layouts.SOCK_FILTER
        Layouts.SOCK_FPROG
        Layouts.IOVEC
        Layouts.MSGHDR
        Layouts.CMSGHDR
        Layouts.SOCKADDR_UN
        Layouts.SECCOMP_DATA
        Layouts.SECCOMP_NOTIF
        Layouts.SECCOMP_NOTIF_RESP
        Layouts.LANDLOCK_RULESET_ATTR
        Layouts.LANDLOCK_PATH_BENEATH_ATTR
    }

    @Test
    fun testSocketSyscalls() {
        // test socket()
        val socketResult =
        LinuxNative.networking.socket(2, 1, 0) // AF_INET, SOCK_STREAM

        if (socketResult is LinuxNative.SyscallResult.Success) {
            val fd = socketResult.asFd()

            // test bind (to a random port)
            nativeScope {
                val addr = allocate(16) // struct sockaddr_in

LinuxNative.networking.bind(fd, addr, 16)

            }


LinuxNative.networking.listen(fd, 5)

            LinuxNative.fileSystem.close(fd)
        }
    }

    @Test
    fun testSocketpair() = nativeScope {
        val fds = allocate(8)
        val result =
        LinuxNative.networking.socketpair(1, 1, 0, fds) // AF_UNIX, SOCK_STREAM

        if (result is LinuxNative.SyscallResult.Success) {
            LinuxNative.fileSystem.close(FileDescriptor.unsafe<FileDescriptorRole.Generic>(fds.readInt(0)))
            LinuxNative.fileSystem.close(FileDescriptor.unsafe<FileDescriptorRole.Generic>(fds.readInt(4)))
        }
    }

    @Test
    fun testProcessVmReadv() = nativeScope {
        // Try reading from our own memory
        val localBuf = allocate(10)
        localBuf.writeByte(0, 123)

        val remoteIovec = IovecSegment.of(allocate(Layouts.IOVEC))
        remoteIovec.setIovBase(localBuf.unwrap)
        remoteIovec.setIovLen(10L)

        val localIovec = IovecSegment.of(allocate(Layouts.IOVEC))
        val destBuf = allocate(10)
        localIovec.setIovBase(destBuf.unwrap)
        localIovec.setIovLen(10L)

        val localIovecManaged = localIovec.managed
        val remoteIovecManaged = remoteIovec.managed

        val result =

LinuxNative.memory.processVmReadv(
    io.mazewall.core.Pid(ProcessHandle.current().pid().toInt()),
    localIovecManaged,
    1,
    remoteIovecManaged,
    1,
    0, // flags
)

    }

    @Test
    fun testFcntl() {
        io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
            val tempFile =
                java.nio.file.Files
                    .createTempFile("fcntl-test", ".txt")
            val path = arena.allocateFrom(tempFile.toString())
            val openResult =
            LinuxNative.fileSystem.open(path, 0)

            val fd = openResult.getFdOrThrow("open")

            val flags =
            LinuxNative.raw.fcntl(fd, 3, 0) // F_GETFL

            assertTrue(flags is LinuxNative.SyscallResult.Success)

            LinuxNative.fileSystem.close(fd)
            tempFile.toFile().delete()
        }
    }

    @Test
    fun testReadlink() = nativeScope {
        val path = allocateFrom("/proc/self/exe")
        val buffer = allocate(1024)
        val result =
        LinuxNative.fileSystem.readlink(path, buffer, 1024)

        assertTrue(result is LinuxNative.SyscallResult.Success)
    }

    @Test
    fun testPoll() = nativeScope {
        val pollFd = PollFdSegment.of(allocate(Layouts.POLLFD))
        pollFd.setFd(-1) // Invalid FD
        pollFd.setEvents(NativeConstants.POLLIN)
        pollFd.setRevents(0)

        val pollFdManaged = pollFd.managed
        val result =
        LinuxNative.raw.poll(pollFdManaged, 1L, 0) // 0 timeout

        assertTrue(result is LinuxNative.SyscallResult.Success)
    }
}
