package io.mazewall.platform.lifecycle

import io.mazewall.*
import io.mazewall.enforcer.api.*
import io.mazewall.ffi.internal.RealNativeEngine
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

@Isolated
class DescriptorIdentitySafetyTest {
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
            val calls = AtomicInteger()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val failures = mutableListOf<Throwable>()
            val fileSystem = object : NativeFileSystem by real.fileSystem {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    calls.incrementAndGet()
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
                    fd.close()
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
}
