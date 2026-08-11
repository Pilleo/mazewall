package io.mazewall.ffi.memory

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeMemory
import io.mazewall.core.Pid
import io.mazewall.core.Tid
import io.mazewall.enforcer.ContainmentViolationException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SupervisorProcessMemoryReaderTest {

    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
    }

    @Test
    fun `test reading bytes returns null for invalid arguments`() {
        val tid = Tid(1234)

        NativeArena.ofConfined().use { arena ->
            with(arena) {
                // Zero remote address
                val res1 = SupervisorProcessMemoryReader.readBytes(tid, 0L, 100)
                assertNull(res1)

                // Zero length
                val res2 = SupervisorProcessMemoryReader.readBytes(tid, 1000L, 0)
                assertNull(res2)
            }
        }
    }

    @Test
    fun `test reading string returns null for zero address`() {
        val tid = Tid(1234)
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                val res = SupervisorProcessMemoryReader.readString(tid, 0L, 100)
                assertNull(res)
            }
        }
    }

    @Test
    fun `test reading string throws exception if no null terminator found`() {
        val tid = Tid(1234)
        val mockMemory = object : MockNativeMemory() {
            override fun processVmReadv(
                pid: Pid,
                localIov: ManagedSegment,
                liovcnt: Long,
                remoteIov: ManagedSegment,
                riovcnt: Long,
                flags: Long
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                // Read base addresses/lengths from descriptor segments
                // localIov has structure of iovec: [base_addr (8 bytes), len (8 bytes)]
                val localBase = localIov.readLong(0)
                val localLen = localIov.readLong(8)

                // Fill the destination buffer with non-zero bytes (e.g., 'A')
                val localBuf = java.lang.foreign.MemorySegment.ofAddress(localBase).reinterpret(localLen)
                localBuf.fill('A'.code.toByte())

                return LinuxNative.SyscallResult.Success(localLen)
            }
        }

        LinuxNative.setEngine(MockNativeEngine(memory = mockMemory))

        NativeArena.ofConfined().use { arena ->
            with(arena) {
                assertThrows(ContainmentViolationException::class.java) {
                    SupervisorProcessMemoryReader.readString(tid, 0x1000L, 100)
                }
            }
        }
    }
}
