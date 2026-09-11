package io.mazewall.ffi.memory

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeMemory
import io.mazewall.core.Tid
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
                pid: io.mazewall.core.Pid,
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
                        TraceeMemoryReader.Real.readBytes(Tid(42), 0x1000L, 8)
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
                pid: io.mazewall.core.Pid,
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
                    assertContentEquals(ByteArray(3), TraceeMemoryReader.Real.readBytes(Tid(42), 0x1000L, 8))
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
                pid: io.mazewall.core.Pid,
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
                            TraceeMemoryReader.Real.readBytes(Tid(42), 0x1000L, 8)
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
