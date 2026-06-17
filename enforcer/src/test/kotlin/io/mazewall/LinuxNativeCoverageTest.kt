package io.mazewall

import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.memory.*
import io.mazewall.seccomp.BpfInstruction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import kotlin.test.*

class LinuxNativeCoverageTest {
    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
    }

    @Test
    fun `test data class methods for SyscallResult`() {
        val res1: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(100L)
        val res2: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(100L)
        val res3: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(1, 200L)

        assertEquals(res1, res2)
        assertNotEquals<LinuxNative.SyscallResult<*, *>>(res1, res3)
        assertEquals(res1.hashCode(), res2.hashCode())
        assertNotNull(res1.toString())
        assertEquals(100L, (res1 as LinuxNative.SyscallResult.Success).value)
        assertEquals(1, (res3 as LinuxNative.SyscallResult.Error).errno)
        assertEquals(200L, res3.rawValue)
    }

    @Test
    fun `test FileDescriptor methods`() {
        val fd1 = FileDescriptor.unsafe<FileDescriptorRole.Generic>(10)
        val fd2 = FileDescriptor.unsafe<FileDescriptorRole.Generic>(10)
        val fd3 = FileDescriptor.unsafe<FileDescriptorRole.Generic>(-1)

        assertEquals(fd1, fd2)
        assertNotEquals(fd1, fd3)
        assertTrue(fd1.isValid)
        assertTrue(fd3.isInvalid)
        assertEquals("fd(10)", fd1.toString())
        assertEquals("fd(-1, closed/invalid)", fd3.toString())
    }

    @Test
    fun `test data class methods for BpfInstruction`() {
        val f1 = BpfInstruction.Jmp(1, 2, 3, 4)
        val f2 = BpfInstruction.Jmp(1, 2, 3, 4)
        val f3 = BpfInstruction.Jmp(1, 0, 0, 0)

        assertEquals(f1, f2)
        assertNotEquals(f1, f3)
        assertEquals(f1.hashCode(), f2.hashCode())
        assertNotNull(f1.toString())
        assertEquals(1, f1.code.toInt())
        assertEquals(2, f1.jt.toInt())
        assertEquals(3, f1.jf.toInt())
        assertEquals(4, f1.k)

        val l1 = BpfInstruction.Ld(0x20, 0x1234)
        assertEquals(0x20.toShort(), l1.code)
        assertEquals(0, l1.jt.toInt())
        assertEquals(0, l1.jf.toInt())
        assertEquals(0x1234, l1.k)
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

        val res = LinuxNative.withTransaction {
            LinuxNative.syscall4(1, io.mazewall.core.NativeArg.IntArg(2), io.mazewall.core.NativeArg.IntArg(3), io.mazewall.core.NativeArg.IntArg(4), io.mazewall.core.NativeArg.IntArg(5))
        }
        assertEquals(444L, res.getOrThrow("test"))
    }

    @Test
    fun `test toLong branches`() {
        // We use LinuxNative methods to test the actual implementation of toLong() in RealNativeEngine
        // null branch
        LinuxNative.withTransaction {
            LinuxNative.syscall(
                -1,
                io.mazewall.core.NativeArg.NullArg,
                io.mazewall.core.NativeArg.NullArg,
                io.mazewall.core.NativeArg.NullArg,
                io.mazewall.core.NativeArg.NullArg,
                io.mazewall.core.NativeArg.NullArg,
                io.mazewall.core.NativeArg.NullArg
            )
        }

        // MemorySegment branch
        nativeScope {
            val seg = allocate(8)
            LinuxNative.withTransaction {
                LinuxNative.syscall(
                    -1,
                    io.mazewall.core.NativeArg.MemoryArg(seg),
                    io.mazewall.core.NativeArg.IntArg(1),
                    io.mazewall.core.NativeArg.IntArg(2),
                    io.mazewall.core.NativeArg.IntArg(3),
                    io.mazewall.core.NativeArg.IntArg(4),
                    io.mazewall.core.NativeArg.IntArg(5)
                )
            }
        }
    }


    @Test
    @EnabledIfLinuxAndSupported
    fun `test newSockFProg manual packing`() = nativeScope {
        val filters = listOf(
            BpfInstruction.Jmp(0x01, 2, 3, 0x12345678),
            BpfInstruction.Ld(0x05, 0x00000001),
        )
        val progSeg = LinuxNative.memory.newSockFProg(filters)
        val prog = SockFprogSegment(progSeg)
        assertNotNull(prog.segment)

        assertEquals(2, prog.getLen().toInt())
        val filterArray = prog.getFilter()
        val f1 = SockFilterSegment(filterArray.asSlice(0, Layouts.SOCK_FILTER_SIZE))

        // Verify first filter
        assertEquals(0x01.toShort(), f1.getCode())
        assertEquals(2, f1.getJt().toInt())
        assertEquals(3, f1.getJf().toInt())
        assertEquals(0x12345678, f1.getK())
    }


    @Test
    fun `test missing syscall wrappers in LinuxNative`() = nativeScope {
        val mock = MockNativeEngine()
        LinuxNative.setEngine(mock)

        val seg = allocate(8)
        val fd = FileDescriptor.unsafe<FileDescriptorRole.Generic>(1)

        mock.networking.acceptResult = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(10)
        assertEquals(10L, LinuxNative.withTransaction {
            LinuxNative.networking.accept(fd, seg, seg)
        }.getOrThrow("test"))

        mock.networking.sendmsgResult = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(20)
        assertEquals(20L, LinuxNative.withTransaction {
            LinuxNative.networking.sendmsg(fd, seg, 0)
        }.getOrThrow("test"))

        mock.networking.recvmsgResult = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(30)
        assertEquals(30L, LinuxNative.withTransaction {
            LinuxNative.networking.recvmsg(fd, seg, 0)
        }.getOrThrow("test"))

        mock.ioctlResult = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(40)
        assertEquals(40L, LinuxNative.withTransaction {
            LinuxNative.ioctl(fd, 2L, seg)
        }.getOrThrow("test"))
        assertEquals(40L, LinuxNative.withTransaction {
            LinuxNative.ioctl(fd, 2L, 3L)
        }.getOrThrow("test"))

        mock.networking.recvResult = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(50)
        assertEquals(50L, LinuxNative.withTransaction {
            LinuxNative.networking.recv(fd, seg, 8L, 0)
        }.getOrThrow("test"))

        assertEquals(1234, LinuxNative.process.gettid().value)
    }
}
