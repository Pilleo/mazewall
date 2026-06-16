package io.mazewall

import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.*
import io.mazewall.seccomp.BpfInstruction
import io.mazewall.core.NativeArg
import org.junit.jupiter.api.Test
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinuxNativeTest : BaseIntegrationTest() {
    @Test
    fun testPrctlGetSeccomp() {
        val result = LinuxNative.withTransaction {
            LinuxNative.process.prctl(
                NativeConstants.PR_GET_SECCOMP,
                NativeArg.NullArg,
                NativeArg.NullArg,
                NativeArg.NullArg,
                NativeArg.NullArg,
            )
        }
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

        val openResult = LinuxNative.withTransaction {
            LinuxNative.fileSystem.open(path, 0) // O_RDONLY
        }
        val fd = openResult.getFdOrThrow("open")

        val buffer = allocate(1024)
        val readResult = LinuxNative.withTransaction {
            LinuxNative.memory.read(fd, buffer, 1024)
        }
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
        val socketResult = LinuxNative.withTransaction {
            LinuxNative.networking.socket(2, 1, 0) // AF_INET, SOCK_STREAM
        }
        if (socketResult is LinuxNative.SyscallResult.Success) {
            val fd = socketResult.asFd()

            // test bind (to a random port)
            nativeScope {
                val addr = allocate(16) // struct sockaddr_in
                LinuxNative.withTransaction {
                    LinuxNative.networking.bind(fd, addr, 16)
                }
            }

            LinuxNative.withTransaction {
                LinuxNative.networking.listen(fd, 5)
            }
            LinuxNative.fileSystem.close(fd)
        }
    }

    @Test
    fun testSocketpair() = nativeScope {
        val fds = allocate(ValueLayout.JAVA_INT, 2)
        val result = LinuxNative.withTransaction {
            LinuxNative.networking.socketpair(1, 1, 0, fds) // AF_UNIX, SOCK_STREAM
        }
        if (result is LinuxNative.SyscallResult.Success) {
            LinuxNative.fileSystem.close(LinuxNative.FileDescriptor(fds.get(ValueLayout.JAVA_INT, 0)))
            LinuxNative.fileSystem.close(LinuxNative.FileDescriptor(fds.get(ValueLayout.JAVA_INT, 4)))
        }
    }

    @Test
    fun testProcessVmReadv() = nativeScope {
        // Try reading from our own memory
        val localBuf = allocate(10)
        localBuf.set(ValueLayout.JAVA_BYTE, 0, 123)

        val remoteIovec = allocate(Layouts.IOVEC)
        remoteIovec.set(ValueLayout.ADDRESS, 0, localBuf)
        remoteIovec.set(ValueLayout.JAVA_LONG, 8, 10)

        val localIovec = allocate(Layouts.IOVEC)
        val destBuf = allocate(10)
        localIovec.set(ValueLayout.ADDRESS, 0, destBuf)
        localIovec.set(ValueLayout.JAVA_LONG, 8, 10)

        val result =
            LinuxNative.withTransaction {
                LinuxNative.memory.processVmReadv(
                    io.mazewall.core.Pid(ProcessHandle.current().pid().toInt()),
                    localIovec,
                    1,
                    remoteIovec,
                    1,
                    0, // flags
                )
            }
    }
}

@Test
fun testFcntl() {
    Arena.ofConfined().use { arena ->
        val tempFile =
            java.nio.file.Files
                .createTempFile("fcntl-test", ".txt")
        val path = arena.allocateFrom(tempFile.toString())
        val openResult = LinuxNative.withTransaction {
            LinuxNative.fileSystem.open(path, 0)
        }
        val fd = openResult.getFdOrThrow("open")

        val flags = LinuxNative.withTransaction {
            LinuxNative.fcntl(fd, 3, 0) // F_GETFL
        }
        assertTrue(flags is LinuxNative.SyscallResult.Success)

        LinuxNative.fileSystem.close(fd)
        tempFile.toFile().delete()
    }

    @Test
    fun testReadlink() = nativeScope {
        val path = allocateFrom("/proc/self/exe")
        val buffer = allocate(1024)
        val result = LinuxNative.withTransaction {
            LinuxNative.fileSystem.readlink(path, buffer, 1024)
        }
        assertTrue(result is LinuxNative.SyscallResult.Success)
    }

    @Test
    fun testPoll() = nativeScope {
        val pollFd = PollFdSegment.allocate()
        pollFd.setFd(-1) // Invalid FD
        pollFd.setEvents(NativeConstants.POLLIN.toShort())
        pollFd.setRevents(0.toShort())

        val result = LinuxNative.withTransaction {
            LinuxNative.poll(pollFd.segment, 1L, 0) // 0 timeout
        }
        assertTrue(result is LinuxNative.SyscallResult.Success)
    }
}
