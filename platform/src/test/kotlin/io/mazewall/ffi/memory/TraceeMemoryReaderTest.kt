package io.mazewall.ffi.memory

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeMemory
import io.mazewall.core.Tid
import io.mazewall.testing.withNativeEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

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
}
