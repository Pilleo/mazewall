package io.mazewall.ffi.memory

import io.mazewall.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.MockNativeEngine
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
import kotlin.test.assertContentEquals

class TraceeMemoryReaderTest {
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
