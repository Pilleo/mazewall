package io.mazewall.enforcer.supervisor

import io.mazewall.core.Tid
import io.mazewall.ffi.memory.NativeArena
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

@EnabledOnOs(OS.LINUX)
class TraceeReadOnlyNulTest {
    @Test
    fun `finds a NUL in this process read-only mapping`() {
        val tid = Tid(ProcessHandle.current().pid().toInt())
        NativeArena.ofConfined().use { arena ->
            val addr = with(arena) { TraceeReadOnlyNul.find(tid) }
            assumeTrue(
                addr != null,
                "process_vm_readv/read-only NUL mapping unavailable on this host",
            )
            assertNotNull(addr)
            assertTrue(addr!! > 0L)
        }
    }
}
