package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
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
