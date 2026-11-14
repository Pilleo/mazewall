package io.mazewall.ffi.memory

import io.mazewall.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.api.MockNativeFileSystem
import io.mazewall.enforcer.api.MockNativeMemory
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.testing.withNativeEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import kotlin.test.assertContentEquals

class TraceeMemoryReaderTest {
    @Test
    fun `captured reader retains selected adapters after ambient engine replacement`() {
        var memoryCalls = 0
        var linkCalls = 0
        val memory = object : MockNativeMemory() {
            override fun processVmReadv(
                pid: Pid,
                localIov: ManagedSegment,
                liovcnt: Long,
                remoteIov: ManagedSegment,
                riovcnt: Long,
                flags: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                memoryCalls++
                assertEquals(42, pid.value)
                assertEquals(1L, liovcnt)
                assertEquals(1L, riovcnt)
                assertEquals(0L, flags)
                val local = IovecSegment.of(localIov)
                val remote = IovecSegment.of(remoteIov)
                assertEquals(8L, local.getIovLen())
                assertEquals(8L, remote.getIovLen())
                assertEquals(0x1234L, remote.getIovBase().address())
                val destination = local.getIovBase().reinterpret(8L)
                MemorySegment.copy(byteArrayOf(0x41, 0x00, 0x7f), 0, destination, ValueLayout.JAVA_BYTE, 0, 3)
                return LinuxNative.SyscallResult.Success(3L)
            }
        }
        val fileSystem = object : MockNativeFileSystem() {
            override fun readlink(
                path: ManagedSegment,
                buf: ManagedSegment,
                bufsiz: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                linkCalls++
                assertEquals("/proc/42/fd/3", path.unwrap.getString(0))
                assertEquals(4096L, bufsiz)
                val bytes = "/selected".toByteArray()
                MemorySegment.copy(bytes, 0, buf.unwrap, ValueLayout.JAVA_BYTE, 0, bytes.size)
                return LinuxNative.SyscallResult.Success(bytes.size.toLong())
            }
        }
        val selected = MockNativeEngine(memory = memory, fileSystem = fileSystem)
        val ambient = MockNativeEngine()
        val reader = TraceeMemoryReader.captured(selected)
        val bytes = withNativeEngine(ambient) {
            NativeArena.ofConfined().use { arena ->
                with(arena) {
                    val result = reader.readBytes(Tid(42), 0x1234L, 8)
                    assertEquals("/selected", reader.resolveLink(Tid(42), "fd/3"))
                    result
                }
            }
        }
        assertContentEquals(byteArrayOf(0x41, 0x00, 0x7f), bytes)
        assertEquals(1, memoryCalls)
        assertEquals(1, linkCalls)
    }

    @Test
    fun `repeated EINTR exhausts a finite process memory read budget`() {
        var attempts = 0
        val memory = object : MockNativeMemory() {
            override fun processVmReadv(
                pid: io.mazewall.platform.lifecycle.Pid,
                localIov: ManagedSegment,
                liovcnt: Long,
                remoteIov: ManagedSegment,
                riovcnt: Long,
                flags: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                attempts++
                return LinuxNative.SyscallResult.Error(io.mazewall.ffi.NativeConstants.EINTR, -1L)
            }
        }

        withNativeEngine(MockNativeEngine(memory = memory)) {
            NativeArena.ofConfined().use { arena ->
                with(arena) {
                    val failure = assertThrows(TraceeMemoryReader.ReadRetryExhaustedException::class.java) {
                        TraceeMemoryReader.real().readBytes(Tid(42), 0x1000L, 8)
                    }
                    assertEquals(failure.attempts, attempts)
                    assertEquals(io.mazewall.ffi.NativeConstants.EINTR, failure.errno)
                }
            }
        }

        assertEquals(8, attempts)
    }

    @Test
    fun `read succeeds when EINTR is followed by a successful attempt`() {
        var attempts = 0
        val memory = object : MockNativeMemory() {
            override fun processVmReadv(
                pid: io.mazewall.platform.lifecycle.Pid,
                localIov: ManagedSegment,
                liovcnt: Long,
                remoteIov: ManagedSegment,
                riovcnt: Long,
                flags: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                if (++attempts == 1) {
                    LinuxNative.SyscallResult.Error(io.mazewall.ffi.NativeConstants.EINTR, -1L)
                } else {
                    LinuxNative.SyscallResult.Success(3L)
                }
        }

        withNativeEngine(MockNativeEngine(memory = memory)) {
            NativeArena.ofConfined().use { arena ->
                with(arena) {
                    assertContentEquals(ByteArray(3), TraceeMemoryReader.real().readBytes(Tid(42), 0x1000L, 8))
                }
            }
        }
        assertEquals(2, attempts)
    }

    @Test
    fun `pre-interrupted read fails before entering native code and preserves interruption`() {
        var attempts = 0
        val memory = object : MockNativeMemory() {
            override fun processVmReadv(
                pid: io.mazewall.platform.lifecycle.Pid,
                localIov: ManagedSegment,
                liovcnt: Long,
                remoteIov: ManagedSegment,
                riovcnt: Long,
                flags: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                attempts++
                return LinuxNative.SyscallResult.Success(1L)
            }
        }

        try {
            Thread.currentThread().interrupt()
            withNativeEngine(MockNativeEngine(memory = memory)) {
                NativeArena.ofConfined().use { arena ->
                    with(arena) {
                        assertThrows(TraceeMemoryReader.ReadInterruptedException::class.java) {
                            TraceeMemoryReader.real().readBytes(Tid(42), 0x1000L, 8)
                        }
                    }
                }
            }
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(0, attempts)
        } finally {
            Thread.interrupted()
        }
    }
}
