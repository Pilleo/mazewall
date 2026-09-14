package io.mazewall.ffi.networking

import io.mazewall.*
import io.mazewall.enforcer.api.*
import io.mazewall.ffi.internal.RealNativeEngine
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.OpenFlags
import io.mazewall.platform.native.*
import io.mazewall.platform.native.getFdOrThrow
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RawManagedBoundaryValidationTest {
    @Test
    fun `managed protection calls validate the full address range`() {
        NativeArena.ofConfined().use { arena ->
            for (number in listOf(Arch.current().mprotect, Arch.current().pkeyMprotect)) {
                val buffer = arena.allocate(16).asSlice(0, 8)
                val args = arrayOf<NativeArg>(NativeArg.MemoryArg(buffer), NativeArg.LongArg(8), NativeArg.LongArg(1), NativeArg.LongArg(-1), NativeArg.LongArg(0), NativeArg.LongArg(0))
                org.junit.jupiter.api
                    .assertDoesNotThrow {
                        io.mazewall.ffi.internal.RawNativeTransfer
                        .validate(number.toLong(), args)
                    }
                args[1] = NativeArg.LongArg(9)
                assertThrows<IllegalArgumentException> {
                    io.mazewall.ffi.internal.RawNativeTransfer
                    .validate(number.toLong(), args)
                }
            }
        }
    }

    @Test
    fun `correctly sized FIONREAD buffers still reach the kernel`() {
        NativeArena.ofConfined().use { arena ->
            val pair = RealNativeEngine.networking.socketpair(1, 1, 0, arena.allocate(8))
            val first = pair.getFdOrThrow("socketpair first")
            val second = pair.getFdOrThrow("socketpair second")
            try {
                val output = arena.allocate(4)
                assertEquals(0L, RealNativeEngine.raw.ioctl(first, 0x541b, output).getOrThrow("FIONREAD"))
                assertEquals(0L, RealNativeEngine.raw.syscall(Arch.current().ioctl.toLong(), NativeArg.FdArg(first), NativeArg.LongArg(0x541b), NativeArg.MemoryArg(output)).getOrThrow("raw FIONREAD"))
            } finally {
                RealNativeEngine.fileSystem.close(first)
                RealNativeEngine.fileSystem.close(second)
            }
        }
    }

    @Test
    fun `raw dispatch rejects managed arguments without a pointer schema`() {
        NativeArena.ofConfined().use { arena ->
            val memory = NativeArg.MemoryArg(arena.allocate(8))
            assertAll(
                { assertThrows<IllegalArgumentException> { RealNativeEngine.raw.syscall(Arch.current().getpid.toLong(), memory) } },
                { assertThrows<IllegalArgumentException> { RealNativeEngine.raw.syscall(Arch.current().read.toLong(), NativeArg.LongArg(-1), NativeArg.NullArg, memory) } },
            )
        }
    }

    @Test
    fun `legacy pointer ioctl requires known payload size in both dispatch paths`() {
        NativeArena.ofConfined().use { arena ->
            val fd = RealNativeEngine.fileSystem.open(arena.allocateFrom("/dev/null"), OpenFlags.RDONLY).getFdOrThrow("open")
            try {
                val short = arena.allocate(8).asSlice(0, 3)
                assertAll(
                    { assertThrows<IllegalArgumentException> { RealNativeEngine.raw.ioctl(fd, 0x541b, short) } },
                    {
                        assertThrows<IllegalArgumentException> {
                            RealNativeEngine.raw.syscall(Arch.current().ioctl.toLong(), NativeArg.FdArg(fd), NativeArg.LongArg(0x541b), NativeArg.MemoryArg(short))
                        }
                    },
                    { assertThrows<IllegalArgumentException> { RealNativeEngine.raw.ioctl(fd, 0x54ff, arena.allocate(8)) } },
                )
            } finally {
                RealNativeEngine.fileSystem.close(fd)
            }
        }
    }

    @Test
    fun `raw poll and ppoll bound the managed pollfd array`() {
        NativeArena.ofConfined().use { arena ->
            // Backing allocation keeps the RED run safe even if native code ignores the slice.
            val short = NativeArg.MemoryArg(arena.allocate(16).asSlice(0, 1))
            val ppoll = if (Arch.current().name == "amd64") 271L else 73L
            assertAll(
                {
                    if (Arch.current().name == "amd64") {
                        assertThrows<IllegalArgumentException> { RealNativeEngine.raw.syscall(7, short, NativeArg.LongArg(1), NativeArg.LongArg(0)) }
                    }
                },
                {
                    assertThrows<IllegalArgumentException> {
                        RealNativeEngine.raw.syscall(ppoll, short, NativeArg.LongArg(1), NativeArg.MemoryArg(arena.allocate(16)))
                    }
                },
            )
        }
    }

    @Test
    fun `raw path output syscalls reject counts beyond the managed output`() {
        NativeArena.ofConfined().use { arena ->
            val arch = Arch.current()
            val path = NativeArg.MemoryArg(arena.allocateFrom("/proc/self/exe"))
            val short = NativeArg.MemoryArg(arena.allocate(4096).asSlice(0, 1))
            assertAll(
                {
                    if (arch.readlink >= 0) assertThrows<IllegalArgumentException> { RealNativeEngine.raw.syscall(arch.readlink.toLong(), path, short, NativeArg.LongArg(2)) }
                },
                {
                    assertThrows<IllegalArgumentException> { RealNativeEngine.raw.syscall(arch.readlinkat.toLong(), NativeArg.LongArg(-100), path, short, NativeArg.LongArg(2)) }
                },
                {
                    assertThrows<IllegalArgumentException> { RealNativeEngine.raw.syscall(arch.getcwd.toLong(), short, NativeArg.LongArg(2)) }
                },
            )
        }
    }

    @Test
    fun `raw counted memory edges reject oversized managed segments`() {
        NativeArena.ofConfined().use { arena ->
            val arch = Arch.current()
            val short = NativeArg.MemoryArg(arena.allocate(32).asSlice(0, 1))
            assertAll(
                { assertThrows<IllegalArgumentException> { RealNativeEngine.raw.syscall(arch.pread64.toLong(), NativeArg.LongArg(-1), short, NativeArg.LongArg(2)) } },
                { assertThrows<IllegalArgumentException> { RealNativeEngine.raw.syscall(arch.pwrite64.toLong(), NativeArg.LongArg(-1), short, NativeArg.LongArg(2)) } },
                { assertThrows<IllegalArgumentException> { RealNativeEngine.raw.syscall(arch.getrandom.toLong(), short, NativeArg.LongArg(2)) } },
                { assertThrows<IllegalArgumentException> { RealNativeEngine.raw.syscall(arch.recvfrom.toLong(), NativeArg.LongArg(-1), short, NativeArg.LongArg(2)) } },
                { assertThrows<IllegalArgumentException> { RealNativeEngine.raw.syscall(arch.sendto.toLong(), NativeArg.LongArg(-1), short, NativeArg.LongArg(2)) } },
                { assertThrows<IllegalArgumentException> { RealNativeEngine.raw.syscall(arch.processVmReadv.toLong(), NativeArg.LongArg(-1), short, NativeArg.LongArg(1)) } },
            )
        }
    }
}
