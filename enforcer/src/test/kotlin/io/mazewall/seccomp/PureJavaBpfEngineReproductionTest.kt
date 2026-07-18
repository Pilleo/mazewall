package io.mazewall.seccomp

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeMemory
import io.mazewall.MockNativeProcess
import io.mazewall.Policy
import io.mazewall.compile
import io.mazewall.core.Arch
import io.mazewall.core.PrctlCommand
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PureJavaBpfEngineReproductionTest {

    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
        PureJavaBpfEngine.clearCache()
        io.mazewall.PolicyCompilationCache.clear()
    }

    @Test
    fun `reproduce no_new_privs being set before filter building failure`() {
        val mockProcess = MockNativeProcess()
        val mockMemory = object : MockNativeMemory() {
            context(arena: NativeArena)
            override fun newSockFProg(filters: List<BpfInstruction>): ManagedSegment {
                throw RuntimeException("Simulated filter building failure")
            }
        }
        val mockEngine = MockNativeEngine(process = mockProcess, memory = mockMemory)
        LinuxNative.setEngine(mockEngine)

        val policy = Policy.builder().build()
        val compiled = policy.definition.compile(Arch.current())

        assertFailsWith<RuntimeException> {
            PureJavaBpfEngine.install(compiled)
        }

        // After the fix, this should be null because buildFilter() is called before setNoNewPrivs()
        assertNull(mockProcess.lastPrctlCommand, "PR_SET_NO_NEW_PRIVS should NOT have been called after the fix")
    }
}
