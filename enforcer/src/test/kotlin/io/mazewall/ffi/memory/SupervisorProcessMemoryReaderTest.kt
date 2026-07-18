package io.mazewall.ffi.memory

import io.mazewall.core.Tid
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.lang.foreign.Arena

class SupervisorProcessMemoryReaderTest {

    @Test
    fun `test reading bytes returns null for invalid arguments`() {
        val tid = Tid(1234)

        Arena.ofConfined().use { arena ->
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
        Arena.ofConfined().use { arena ->
            with(arena) {
                val res = SupervisorProcessMemoryReader.readString(tid, 0L, 100)
                assertNull(res)
            }
        }
    }
}
