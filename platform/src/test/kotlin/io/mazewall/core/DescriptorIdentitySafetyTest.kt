package io.mazewall.platform.lifecycle

import io.mazewall.*
import io.mazewall.enforcer.api.*
import io.mazewall.ffi.internal.RealNativeEngine
import io.mazewall.ffi.internal.SyscallInvoker
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.readInt
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.NativeFileSystem
import io.mazewall.platform.native.getFdOrThrow
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Isolated
import java.lang.foreign.MemorySegment
import java.lang.invoke.MethodHandles
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

@Isolated
class DescriptorIdentitySafetyTest {
    @Test
    fun `real acquired descriptor closes successfully`() {
        NativeArena.ofConfined().use { arena ->
            val fd = RealNativeEngine.fileSystem.open(arena.allocateFrom("/dev/null"), OpenFlags.RDONLY).getFdOrThrow("open")
            val alias = FileDescriptor.withRole(fd, FileDescriptorRole.UnixSocket)
            fd.close(RealNativeEngine.fileSystem)
            assertFalse(alias.isLiveForIo())
            alias.close(RealNativeEngine.fileSystem)
        }
    }

    @Test
    fun `pre-claim exception retains original ownership`() {
        val arena = NativeArena.ofConfined()
        val fd = TestFileDescriptors.adopt(987651, FileDescriptorRole.Generic, arena)
        val alias = FileDescriptor.withRole(fd, FileDescriptorRole.UnixSocket)
        val fs = object : NativeFileSystem by RealNativeEngine.fileSystem {
            override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                SyscallInvoker.close(MethodHandles.constant(Int::class.javaPrimitiveType, 0), fd)
        }
        assertThrows<IllegalArgumentException> { fd.close(fs) }
        assertTrue(fd.isLiveForIo())
        assertTrue(arena.isAlive)
        val success = object : NativeFileSystem by RealNativeEngine.fileSystem {
            override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                check(fd.claimForClose())
                return LinuxNative.SyscallResult.Success(0L)
            }
        }
        alias.close(success)
        assertFalse(arena.isAlive)
    }

    @Test
    fun `failure after claim cannot restore ownership`() {
        val fd = TestFileDescriptors.adopt(987650, FileDescriptorRole.Generic)
        val alias = FileDescriptor.withRole(fd, FileDescriptorRole.UnixSocket)
        val failure = IllegalStateException("injected")
        val throwing = MethodHandles.dropArguments(
            MethodHandles.insertArguments(
                MethodHandles.throwException(Int::class.javaPrimitiveType, IllegalStateException::class.java),
                0,
                failure,
            ),
            0,
            MemorySegment::class.java,
            Int::class.javaPrimitiveType,
        )
        var calls = 0
        val fs = object : NativeFileSystem by RealNativeEngine.fileSystem {
            override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                calls++
                return SyscallInvoker.close(throwing, fd)
            }
        }
        assertSame(failure, assertThrows<IllegalStateException> { fd.close(fs) })
        assertFalse(fd.isLiveForIo())
        assertFalse(alias.isLiveForIo())
        alias.close(fs)
        assertEquals(1, calls)
    }

    @Test
    fun `socketpair acquisition carries exactly two distinct descriptor receipts`() {
        NativeArena.ofConfined().use { arena ->
            val pair = arena.allocate(8)
            val result = RealNativeEngine.networking.socketpair(1, 1, 0, pair)
            result.getOrThrow("socketpair")
            var first: FileDescriptor<FileDescriptorRole.Generic, FdState.Open, FdOwnership.Owned>? = null
            var second: FileDescriptor<FileDescriptorRole.Generic, FdState.Open, FdOwnership.Owned>? = null
            try {
                first = result.getFdOrThrow("first socket")
                second = result.getFdOrThrow("second socket")
                assertNotEquals(first.value, second.value)
                assertThrows<IllegalStateException> { result.getFdOrThrow("already consumed") }
            } finally {
                RealNativeEngine.fileSystem.close(first ?: TestFileDescriptors.adopt(pair.readInt(0), FileDescriptorRole.Generic))
                RealNativeEngine.fileSystem.close(second ?: TestFileDescriptors.adopt(pair.readInt(4), FileDescriptorRole.Generic))
            }
        }
    }

    @Test
    fun `ordinary numeric success is not an acquisition receipt`() {
        val result = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(987653)
        assertThrows<IllegalStateException> { result.getFdOrThrow("forged result") }
    }

    @Test
    fun `raw integer factories cannot mint owned close capabilities`() {
        assertThrows<IllegalStateException> { FileDescriptor.adopt(987654, FileDescriptorRole.Generic) }
        assertThrows<IllegalStateException> { FileDescriptor.replace<FileDescriptorRole.Generic>(987654) }
    }

    @Test
    fun `concurrent closes dispatch exactly once and retire even when close throws`() {
        NativeArena.ofConfined().use { arena ->
            val real = RealNativeEngine
            val raw = real.fileSystem
                .open(arena.allocateFrom("/dev/null"), OpenFlags.RDONLY)
                .getOrThrow("open")
                .toInt()
            val fd = TestFileDescriptors
                .adopt(raw, FileDescriptorRole.Generic)
            val alias = FileDescriptor.withRole(fd, FileDescriptorRole.UnixSocket)
            val calls = AtomicInteger()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val failures = mutableListOf<Throwable>()
            val fileSystem = object : NativeFileSystem by real.fileSystem {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    calls.incrementAndGet()
                    check(fd.claimForClose())
                    entered.countDown()
                    release.await()
                    throw IllegalStateException("injected close failure")
                }
            }
            LinuxNative.setEngine(object : NativeEngine by real {
                override val fileSystem = fileSystem
            })
            try {
                val first = Thread.ofPlatform().start {
                    try {
                    alias.close()
                } catch (t: Throwable) {
                    synchronized(failures) { failures.add(t) }
                }
                }
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                val second = Thread.ofPlatform().start {
                    try {
                    fd.close()
                } catch (t: Throwable) {
                    synchronized(failures) { failures.add(t) }
                }
                }
                release.countDown()
                first.join(5000)
                second.join(5000)
                assertEquals(1, calls.get())
                assertFalse(fd.isLiveForIo())
            } finally {
                release.countDown()
                LinuxNative.resetToDefault()
                real.fileSystem.close(
                    TestFileDescriptors
                    .adopt(raw, FileDescriptorRole.Generic),
                )
            }
        }
    }

    private fun realFd(arena: NativeArena): Int =
        RealNativeEngine
            .fileSystem
            .open(arena.allocateFrom("/dev/null"), OpenFlags.RDONLY)
            .getOrThrow("open")
            .toInt()

    @Test
    fun `native close Error is observable and terminal`() {
        NativeArena.ofConfined().use { arena ->
            val real = RealNativeEngine
            val raw = realFd(arena)
            val fd = TestFileDescriptors.adopt(raw, FileDescriptorRole.Generic)
            val calls = AtomicInteger()
            val fileSystem = object : NativeFileSystem by real.fileSystem {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    calls.incrementAndGet()
                    return LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(io.mazewall.ffi.NativeConstants.EIO, -1L)
                }
            }
            LinuxNative.setEngine(object : NativeEngine by real {
                override val fileSystem = fileSystem
            })
            try {
                val failure = assertThrows<NativeCloseFailure> { fd.close() }
                assertTrue(failure.message.orEmpty().contains("errno=5"), "expected observable errno: ${failure.message}")
                assertEquals(1, calls.get())
                assertFalse(fd.isLiveForIo())
                // Terminal: a second close dispatches nothing and does not throw.
                fd.close()
                assertEquals(1, calls.get())
            } finally {
                LinuxNative.resetToDefault()
                real.fileSystem.close(TestFileDescriptors.adopt(raw, FileDescriptorRole.Generic))
            }
        }
    }

    @Test
    fun `use attaches close Error as suppressed evidence`() {
        NativeArena.ofConfined().use { arena ->
            val real = RealNativeEngine
            val raw = realFd(arena)
            val fd = TestFileDescriptors.adopt(raw, FileDescriptorRole.Generic)
            val fileSystem = object : NativeFileSystem by real.fileSystem {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                    LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(io.mazewall.ffi.NativeConstants.EIO, -1L)
            }
            LinuxNative.setEngine(object : NativeEngine by real {
                override val fileSystem = fileSystem
            })
            try {
                val failure = assertThrows<IllegalArgumentException> { fd.use { throw IllegalArgumentException("block failed") } }
                assertEquals("block failed", failure.message)
                assertTrue(failure.suppressed.any { it is NativeCloseFailure && it.message.orEmpty().contains("errno=5") })
            } finally {
                LinuxNative.resetToDefault()
                real.fileSystem.close(TestFileDescriptors.adopt(raw, FileDescriptorRole.Generic))
            }
        }
    }

    @Test
    fun `pre-dispatch throw retains ownership for one later successful cleanup`() {
        NativeArena.ofConfined().use { arena ->
            val real = RealNativeEngine
            val raw = realFd(arena)
            val fd = TestFileDescriptors.adopt(raw, FileDescriptorRole.Generic)
            val calls = AtomicInteger()
            val fileSystem = object : NativeFileSystem by real.fileSystem {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    if (calls.incrementAndGet() == 1) throw PreDispatchCloseFailure("pre-dispatch", IllegalStateException("pre-dispatch"))
                    return LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
                }
            }
            LinuxNative.setEngine(object : NativeEngine by real {
                override val fileSystem = fileSystem
            })
            try {
                assertThrows<PreDispatchCloseFailure> { fd.close() }
                assertTrue(fd.isLiveForIo())
                val closed = fd.close()
                assertEquals(2, calls.get())
                assertTrue(closed.isClosedType())
                assertFalse(fd.isLiveForIo())
            } finally {
                LinuxNative.resetToDefault()
                real.fileSystem.close(TestFileDescriptors.adopt(raw, FileDescriptorRole.Generic))
            }
        }
    }

    @Test
    fun `distinct typed views cannot dispatch twice`() {
        NativeArena.ofConfined().use { arena ->
            val real = RealNativeEngine
            val raw = realFd(arena)
            val fd = TestFileDescriptors.adopt(raw, FileDescriptorRole.Generic)
            val view = FileDescriptor.withRole(fd, FileDescriptorRole.UnixSocket)
            val calls = AtomicInteger()
            val fileSystem = object : NativeFileSystem by real.fileSystem {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    calls.incrementAndGet()
                    return LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
                }
            }
            LinuxNative.setEngine(object : NativeEngine by real {
                override val fileSystem = fileSystem
            })
            try {
                fd.close()
                view.close()
                fd.close()
                assertEquals(1, calls.get())
                assertFalse(fd.isLiveForIo())
                assertFalse(view.isLiveForIo())
            } finally {
                LinuxNative.resetToDefault()
                real.fileSystem.close(TestFileDescriptors.adopt(raw, FileDescriptorRole.Generic))
            }
        }
    }

    @Test
    fun `arena failure cannot replace the close failure`() {
        NativeArena.ofConfined().use { arena ->
            val real = RealNativeEngine
            val raw = realFd(arena)
            val failingArena = NativeArena(
                object : java.lang.foreign.Arena by java.lang.foreign.Arena
                    .ofConfined() {
                    override fun close() {
                        throw IllegalStateException("arena close failed")
                    }
                },
                isShared = false,
            )
            val fd = TestFileDescriptors.adopt(raw, FileDescriptorRole.Generic, failingArena)
            val fileSystem = object : NativeFileSystem by real.fileSystem {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                    LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(io.mazewall.ffi.NativeConstants.EIO, -1L)
            }
            LinuxNative.setEngine(object : NativeEngine by real {
                override val fileSystem = fileSystem
            })
            try {
                val failure = assertThrows<NativeCloseFailure> { fd.close() }
                assertTrue(failure.message.orEmpty().contains("errno=5"), "expected observable errno: ${failure.message}")
                assertTrue(failure.suppressed.any { it.message == "arena close failed" })
                assertFalse(fd.isLiveForIo())
            } finally {
                LinuxNative.resetToDefault()
                real.fileSystem.close(TestFileDescriptors.adopt(raw, FileDescriptorRole.Generic))
            }
        }
    }

    @Test
    fun `arena failure propagates when close succeeds`() {
        NativeArena.ofConfined().use { arena ->
            val real = RealNativeEngine
            val raw = realFd(arena)
            val failingArena = NativeArena(
                object : java.lang.foreign.Arena by java.lang.foreign.Arena
                    .ofConfined() {
                    override fun close() {
                        throw IllegalStateException("arena close failed")
                    }
                },
                isShared = false,
            )
            val fd = TestFileDescriptors.adopt(raw, FileDescriptorRole.Generic, failingArena)
            val fileSystem = object : NativeFileSystem by real.fileSystem {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                    LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
            }
            LinuxNative.setEngine(object : NativeEngine by real {
                override val fileSystem = fileSystem
            })
            try {
                val failure = assertThrows<IllegalStateException> { fd.close() }
                assertEquals("arena close failed", failure.message)
                assertFalse(fd.isLiveForIo())
            } finally {
                LinuxNative.resetToDefault()
                real.fileSystem.close(TestFileDescriptors.adopt(raw, FileDescriptorRole.Generic))
            }
        }
    }
}
