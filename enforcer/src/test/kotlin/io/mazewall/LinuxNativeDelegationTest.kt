package io.mazewall

import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.ForeignFdGuard
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.memory.*
import io.mazewall.seccomp.BpfInstruction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.test.*

@ExtendWith(ForeignFdGuard::class)
class LinuxNativeDelegationTest {
    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
    }

    @Test
    fun `test LinuxNative engine delegation getters`() {
        assertNotNull(LinuxNative.fileSystem)
        assertNotNull(LinuxNative.networking)
        assertNotNull(LinuxNative.process)
        assertNotNull(LinuxNative.memory)
    }

    @Test
    fun `test LinuxNative syscall4 delegate`() {
        val mock = MockNativeEngine()
        mock.syscallResult = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(444)
        LinuxNative.setEngine(mock)

        val res = LinuxNative.raw.syscall4(
            1,
            io.mazewall.core.NativeArg
            .IntArg(2),
                io.mazewall.core.NativeArg
                .IntArg(3),
                    io.mazewall.core.NativeArg
                    .IntArg(4),
                        io.mazewall.core.NativeArg
                        .IntArg(5),
        )
        assertEquals(444L, res.getOrThrow("test"))
    }

    @Test
    fun `raw syscall delegates null and memory arguments to configured engine`() =
        nativeScope {
        val observed = mutableListOf<io.mazewall.core.NativeArg>()
        val mock = MockNativeEngine().apply {
            onSyscall = { _, a1, a2, _, _, _, _ ->
                observed += a1
                observed += a2
                LinuxNative.SyscallResult.Success(17L)
            }
        }
        LinuxNative.setEngine(mock)

        val segment = allocate(8)
        val result = LinuxNative.raw.syscall(
            123,
            io.mazewall.core.NativeArg.NullArg,
            io.mazewall.core.NativeArg
                .MemoryArg(segment),
            io.mazewall.core.NativeArg
                .IntArg(1),
            io.mazewall.core.NativeArg
                .IntArg(2),
            io.mazewall.core.NativeArg
                .IntArg(3),
            io.mazewall.core.NativeArg
                .IntArg(4),
        )

        assertEquals(17L, result.getOrThrow("delegated syscall"))
        assertEquals(io.mazewall.core.NativeArg.NullArg, observed[0])
        assertEquals(
            io.mazewall.core.NativeArg
            .MemoryArg(segment),
                observed[1],
        )
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `test newSockFProg manual packing`() =
        nativeScope {
        val filters = listOf(
            BpfInstruction.Jmp(0x01, 2, 3, 0x12345678),
            BpfInstruction.Ld(0x05, 0x00000001),
        )
        val prog = SockFprogSegment.of(LinuxNative.memory.newSockFProg(filters))
        assertNotNull(prog.segment)

        assertEquals(2, prog.getLen().toInt())
        val f1 = SockFilterSegment.of(prog.managedFilter.asSlice(0, Layouts.SOCK_FILTER_SIZE))

        // Verify first filter
        assertEquals(0x01.toShort(), f1.getCode())
        assertEquals(2, f1.getJt().toInt())
        assertEquals(3, f1.getJf().toInt())
        assertEquals(0x12345678, f1.getK())
    }

    @Test
    fun `test missing syscall wrappers in LinuxNative`() =
        nativeScope {
        val mock = MockNativeEngine()
        LinuxNative.setEngine(mock)

        val seg = allocate(8)
        val fd = FileDescriptor.replace<FileDescriptorRole.Generic>(1)

        mock.networking.acceptResult = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(10)
        assertEquals(10L, LinuxNative.networking.accept(fd, seg, seg).getOrThrow("test"))

        mock.networking.sendmsgResult = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(20)
        assertEquals(20L, LinuxNative.networking.sendmsg(fd, seg, 0).getOrThrow("test"))

        mock.networking.recvmsgResult = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(30)
        assertEquals(30L, LinuxNative.networking.recvmsg(fd, seg, 0).getOrThrow("test"))

        mock.ioctlResult = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(40)
        assertEquals(40L, LinuxNative.raw.ioctl(fd, 2L, seg).getOrThrow("test"))
        assertEquals(40L, LinuxNative.raw.ioctl(fd, 2L, 3L).getOrThrow("test"))

        mock.networking.recvResult = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(50)
        assertEquals(50L, LinuxNative.networking.recv(fd, seg, 8L, 0).getOrThrow("test"))

        assertEquals(1234, LinuxNative.process.gettid().value)
    }
}
