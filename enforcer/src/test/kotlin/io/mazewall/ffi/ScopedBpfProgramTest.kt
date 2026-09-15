package io.mazewall.ffi

import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.native
import io.mazewall.platform.native.LinuxNative
import java.lang.foreign.ValueLayout
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ScopedBpfProgramTest {
    @Test
    fun `native BPF program is live only inside installation callback`() {
        var escaped: ManagedSegment? = null

        ScopedBpfProgram.withProgram(emptyList(), LinuxNative) { program ->
            escaped = program
            assertNotEquals(0L, program.address())
        }

        assertFailsWith<IllegalStateException> {
            escaped!!.native.get(ValueLayout.JAVA_BYTE, 0)
        }
    }
}
