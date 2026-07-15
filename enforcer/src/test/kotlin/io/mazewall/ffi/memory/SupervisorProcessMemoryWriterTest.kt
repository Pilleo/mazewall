package io.mazewall.ffi.memory

import io.mazewall.core.Tid
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.lang.foreign.Arena

class SupervisorProcessMemoryWriterTest {

    @Test
    fun `test writing bytes returns false for invalid arguments`() {
        val tid = Tid(1234)

        Arena.ofConfined().use { arena ->
            with(arena) {
                // Zero remote address
                val res1 = SupervisorProcessMemoryWriter.writeBytes(tid, 0L, byteArrayOf(1, 2, 3))
                assertFalse(res1)

                // Empty bytes
                val res2 = SupervisorProcessMemoryWriter.writeBytes(tid, 1000L, ByteArray(0))
                assertFalse(res2)
            }
        }
    }
}
