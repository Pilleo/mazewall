package io.mazewall.ffi

import io.mazewall.ffi.internal.RealNativeEngine
import io.mazewall.ffi.internal.RealNativeHelper
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.SharedSegment
import io.mazewall.ffi.memory.readInt
import io.mazewall.ffi.memory.writeByte
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.OpenFlags
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.getFdOrThrow
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import io.mazewall.platform.syscall.PrctlCommand
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.Executors

class RealNativeHelperDowncallTest {
    @Test
    fun `missing libc symbols preserve expected handle shape across return layouts and errno capture`() {
        val missingName = "__mazewall_nonexistent_symbol__"
        Arena.ofConfined().use { arena ->
            val capture = arena.allocate(Linker.Option.captureStateLayout())
            val captureOption = Linker.Option.captureCallState("errno")

            val intHandle =
                RealNativeHelper.downcall(
                    missingName,
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                )
            assertThrows<UnsupportedOperationException> {
                intHandle.invokeExact(1) as Int
            }

            val intErrnoHandle =
                RealNativeHelper.downcall(
                    missingName,
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                    captureOption,
                )
            assertThrows<UnsupportedOperationException> {
                intErrnoHandle.invokeExact(capture, 1) as Int
            }

            val longErrnoHandle =
                RealNativeHelper.downcall(
                    missingName,
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG),
                    Linker.Option.firstVariadicArg(1),
                    captureOption,
                )
            assertThrows<UnsupportedOperationException> {
                longErrnoHandle.invokeExact(capture, 42L) as Long
            }

            val ptrErrnoHandle =
                RealNativeHelper.downcall(
                    missingName,
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                    captureOption,
                )
            assertThrows<UnsupportedOperationException> {
                ptrErrnoHandle.invokeExact(capture, MemorySegment.NULL) as MemorySegment
            }

            val voidHandle =
                RealNativeHelper.downcall(
                    missingName,
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT),
                )
            assertThrows<UnsupportedOperationException> {
                voidHandle.invokeExact(7)
            }
        }
    }

    @Test
    fun `prctl validates managed pointer arguments before native dispatch`() {
        NativeArena.ofConfined().use { arena ->
            val validGetName = arena.allocate(16)
            val result = RealNativeEngine.process.prctl(PrctlCommand.GetName(NativeArg.MemoryArg(validGetName)))
            assertTrue(result is LinuxNative.SyscallResult.Success)

            val shortGetName = arena.allocate(15)
            assertThrows<IllegalArgumentException> {
                RealNativeEngine.process.prctl(PrctlCommand.GetName(NativeArg.MemoryArg(shortGetName)))
            }

            val readOnlyGetName = SharedSegment(Arena.ofAuto().allocate(16).asReadOnly())
            assertThrows<IllegalArgumentException> {
                RealNativeEngine.process.prctl(PrctlCommand.GetName(NativeArg.MemoryArg(readOnlyGetName)))
            }

            val unterminatedName = arena.allocate(8)
            for (i in 0L until 8L) {
                unterminatedName.writeByte(i, 'a'.code.toByte())
            }
            assertThrows<IllegalArgumentException> {
                RealNativeEngine.process.prctl(PrctlCommand.SetName(NativeArg.MemoryArg(unterminatedName)))
            }

            val shortFprog = arena.allocate(8)
            assertThrows<IllegalArgumentException> {
                RealNativeEngine.process.prctl(
                    PrctlCommand.SetSeccomp(
                        NativeConstants.SECCOMP_MODE_FILTER.toLong(),
                        NativeArg.MemoryArg(shortFprog),
                    ),
                )
            }
        }

        val closed =
            NativeArena.ofConfined().use { temp ->
                temp.allocate(16)
            }
        assertThrows<IllegalStateException> {
            RealNativeEngine.process.prctl(PrctlCommand.GetName(NativeArg.MemoryArg(closed)))
        }

        NativeArena.ofConfined().use { confined ->
            val segment = confined.allocate(16)
            Executors.newSingleThreadExecutor().use { otherThread ->
                val error =
                    otherThread
                        .submit<Throwable?> {
                            try {
                                RealNativeEngine.process.prctl(PrctlCommand.GetName(NativeArg.MemoryArg(segment)))
                                null
                            } catch (t: Throwable) {
                                t
                            }
                        }.get()
                assertTrue(error is IllegalStateException)
            }
        }
    }

    @Test
    fun `aarch64 renameat uses Linux generic syscall number 38`() {
        assertEquals(38, Arch.AARCH64.renameat)
        assertEquals(38, Syscall.RENAMEAT.numberFor(Arch.AARCH64))
    }

    @Test
    fun `variadic libc downcalls succeed with live kernel arguments and errno capture`(
        @TempDir tempDir: Path,
    ) {
        exerciseVariadicDowncalls(tempDir)
    }

    fun exerciseVariadicDowncalls(tempDir: Path) {
        val zero = NativeArg.LongArg(0L)
        val arch = Arch.current()
        NativeArena.ofConfined().use { arena ->
            assertEquals(
                ProcessHandle.current().pid(),
                RealNativeEngine.raw.syscall(arch.getpid.toLong(), zero, zero, zero, zero, zero, zero).getOrThrow("syscall(getpid)"),
            )
            val randomBuf = arena.allocate(8L)
            assertEquals(
                8L,
                RealNativeEngine.raw
                    .syscall(
                        arch.getrandom.toLong(),
                        NativeArg.MemoryArg(randomBuf),
                        NativeArg.LongArg(8L),
                        zero,
                        zero,
                        zero,
                        zero,
                    ).getOrThrow("syscall(getrandom)"),
            )

            val seccompState = RealNativeEngine.process.prctl(PrctlCommand.GetSeccomp).getOrThrow("prctl(PR_GET_SECCOMP)")
            assertTrue(seccompState == 0L || seccompState == 2L)
            val commBuf = arena.allocate(16L)
            assertEquals(
                0L,
                RealNativeEngine.process.prctl(PrctlCommand.GetName(NativeArg.MemoryArg(commBuf))).getOrThrow("prctl(PR_GET_NAME)"),
            )

            val createFlags = OpenFlags(O_RDWR or O_CREAT or O_EXCL or NativeConstants.O_CLOEXEC)
            val openPath = tempDir.resolve("variadic-open.txt")
            val openedFd =
                RealNativeEngine.fileSystem
                    .open(arena.allocateFrom(openPath.toString()), createFlags, MODE_0600)
                    .getFdOrThrow("open(O_CREAT)")
            try {
                assertEquals(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(openPath),
                )
            } finally {
                RealNativeEngine.fileSystem.close(openedFd).getOrThrow("close(open)")
            }

            val duplicateOpen =
                RealNativeEngine.fileSystem.open(arena.allocateFrom(openPath.toString()), createFlags, MODE_0600)
            assertTrue(duplicateOpen is LinuxNative.SyscallResult.Error && duplicateOpen.errno == EEXIST)

            val openatPath = tempDir.resolve("variadic-openat.txt")
            val openatFd =
                RealNativeEngine.fileSystem
                    .openat(FileDescriptor.AT_FDCWD, arena.allocateFrom(openatPath.toString()), createFlags, MODE_0400)
                    .getFdOrThrow("openat(O_CREAT)")
            try {
                assertEquals(
                    setOf(PosixFilePermission.OWNER_READ),
                    Files.getPosixFilePermissions(openatPath),
                )
            } finally {
                RealNativeEngine.fileSystem.close(openatFd).getOrThrow("close(openat)")
            }

            val pair = arena.allocate(2L * Int.SIZE_BYTES)
            val pairResult = RealNativeEngine.networking.socketpair(AF_UNIX, SOCK_STREAM, 0, pair)
            pairResult.getOrThrow("socketpair")
            check(pairResult is LinuxNative.SyscallResult.Success)
            val first = pairResult.takeDescriptorReceipt().consume(FileDescriptorRole.UnixSocket)
            val second = pairResult.takeDescriptorReceipt().consume(FileDescriptorRole.UnixSocket)
            try {
                assertEquals(0L, RealNativeEngine.raw.fcntl(first, NativeConstants.F_GETFD, 0L).getOrThrow("fcntl(first pre)"))
                assertEquals(
                    0L,
                    RealNativeEngine.raw.fcntl(first, NativeConstants.F_SETFD, NativeConstants.FD_CLOEXEC.toLong()).getOrThrow("fcntl(F_SETFD)"),
                )
                assertEquals(
                    NativeConstants.FD_CLOEXEC.toLong(),
                    RealNativeEngine.raw.fcntl(first, NativeConstants.F_GETFD, 0L).getOrThrow("fcntl(first post)"),
                )

                assertEquals(0L, RealNativeEngine.raw.fcntl(second, NativeConstants.F_GETFD, 0L).getOrThrow("fcntl(second pre)"))
                assertEquals(0L, RealNativeEngine.raw.ioctl(second, FIOCLEX, 0L).getOrThrow("ioctl(FIOCLEX)"))
                assertEquals(
                    NativeConstants.FD_CLOEXEC.toLong(),
                    RealNativeEngine.raw.fcntl(second, NativeConstants.F_GETFD, 0L).getOrThrow("fcntl(second post)"),
                )

                val payload = arena.allocate(3L)
                ManagedSegment.copy(byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte()), 0, payload, 0L, 3)
                assertEquals(3L, RealNativeEngine.memory.write(first, payload, 3L).getOrThrow("write(socketpair)"))

                val countBuf = arena.allocate(Int.SIZE_BYTES.toLong())
                assertEquals(0L, RealNativeEngine.raw.ioctl(second, FIONREAD, countBuf).getOrThrow("ioctl(FIONREAD)"))
                assertEquals(3, countBuf.readInt(0L))
            } finally {
                RealNativeEngine.fileSystem.close(first).getOrThrow("close(first)")
                RealNativeEngine.fileSystem.close(second).getOrThrow("close(second)")
            }
        }
    }

    private companion object {
        private const val AF_UNIX = 1
        private const val SOCK_STREAM = 1
        private const val O_RDWR = 0x2
        private const val O_CREAT = 0x40
        private const val O_EXCL = 0x80
        private const val EEXIST = 17
        private const val FIOCLEX = 0x5451L
        private const val FIONREAD = 0x541BL
        private const val MODE_0600 = 0b110_000_000
        private const val MODE_0400 = 0b100_000_000
    }
}
