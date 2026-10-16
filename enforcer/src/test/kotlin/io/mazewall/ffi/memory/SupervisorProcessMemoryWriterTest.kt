package io.mazewall.ffi.memory

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class SupervisorProcessMemoryWriterTest {
    @Test
    fun `test writing bytes returns false for invalid arguments`() {
        val tid = Tid(1234)

        NativeArena.ofConfined().use { arena ->
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
