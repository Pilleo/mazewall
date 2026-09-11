package io.mazewall.ffi.networking

import io.mazewall.LinuxNative
import io.mazewall.NativeMemory
import io.mazewall.core.*
import io.mazewall.core.TestFileDescriptors
import io.mazewall.ffi.internal.RealNativeEngine
import io.mazewall.ffi.memory.*
import io.mazewall.getFdOrThrow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NativeTransferValidationTest {
    @Test
    fun `raw read rejects a count beyond its managed segment`() {
        NativeArena.ofConfined().use { arena ->
            val fd = RealNativeEngine.fileSystem.open(arena.allocateFrom("/dev/null"), OpenFlags.RDONLY).getFdOrThrow("open")
            try {
                assertThrows<IllegalArgumentException> {
                    RealNativeEngine.raw.syscall(Arch.current().read.toLong(), NativeArg.FdArg(fd), NativeArg.MemoryArg(arena.allocate(4)), NativeArg.LongArg(5))
                }
            } finally {
                RealNativeEngine.fileSystem.close(fd)
            }
        }
    }

    @Test
    fun `read and write reject negative and oversized counts before native dispatch`() {
        NativeArena.ofConfined().use { arena ->
            val result = RealNativeEngine.fileSystem.open(arena.allocateFrom("/dev/null"), OpenFlags(2))
            val fd = TestFileDescriptors
                .adopt(result.getOrThrow("open").toInt(), FileDescriptorRole.Generic)
            try {
                val buffer = arena.allocate(4)
                for (count in listOf(-1L, 5L, Long.MAX_VALUE)) {
                    assertThrows<IllegalArgumentException> { RealNativeEngine.memory.read(fd, buffer, count) }
                    assertThrows<IllegalArgumentException> { RealNativeEngine.memory.write(fd, buffer, count) }
                }
            } finally {
                RealNativeEngine.fileSystem.close(fd)
            }
        }
    }

    @Test
    fun `recv and poll reject out of segment counts even on invalid descriptors`() {
        NativeArena.ofConfined().use { arena ->
            val buffer = arena.allocate(4)
            for (count in listOf(-1L, 5L, Long.MAX_VALUE)) {
                assertThrows<IllegalArgumentException> { RealNativeEngine.networking.recv(FileDescriptor.generic(-1), buffer, count, 0) }
                assertThrows<IllegalArgumentException> { RealNativeEngine.raw.poll(buffer, count, 0) }
            }
        }
    }

    @Test
    fun `bulk stream validates every array range before reading including zero length`() {
        NativeArena.ofConfined().use { arena ->
            val memory = object : NativeMemory by RealNativeEngine.memory {
                override fun read(
                    fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    buf: ManagedSegment,
                    count: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = throw AssertionError("invalid range reached native read")
            }
            val stream = NativeDescriptorInputStream(
                TestFileDescriptors
                .replace<FileDescriptorRole.Generic>(-1),
                    arena,
                "test",
                memory,
            )
            for ((off, len) in listOf(-1 to 0, 5 to 0, 0 to -1, 3 to 2, Int.MAX_VALUE to 1)) {
                assertThrows<IndexOutOfBoundsException> { stream.read(ByteArray(4), off, len) }
            }
            assertEquals(0, stream.read(ByteArray(4), 4, 0))
        }
    }

    @Test
    fun `bulk stream rejects impossible native result before copying`() {
        NativeArena.ofConfined().use { arena ->
            val memory = object : NativeMemory by RealNativeEngine.memory {
                override fun read(
                    fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    buf: ManagedSegment,
                    count: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(count + 1)
            }
            val stream = NativeDescriptorInputStream(
                TestFileDescriptors
                .replace<FileDescriptorRole.Generic>(-1),
                    arena,
                "test",
                memory,
            )
            assertThrows<java.io.IOException> { stream.read(ByteArray(4), 0, 1) }
        }
    }
}
