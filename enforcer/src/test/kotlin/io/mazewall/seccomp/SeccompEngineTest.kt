package io.mazewall.seccomp
import io.mazewall.Policy
import io.mazewall.CompiledSandbox
import io.mazewall.compile
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SeccompEngineTest {
    @Test
    fun `default installOnProcess throws UnsupportedOperationException`() {
        val dummyEngine = object : SeccompEngine<EngineState> {
            override val state: EngineState
                get() = object : EngineState.Unprivileged {}

            override fun install(policy: CompiledSandbox<*>): SeccompEngine<EngineState.Loaded> {
                @Suppress("UNCHECKED_CAST")
                return this as SeccompEngine<EngineState.Loaded>
            }

            override val isSupported: Boolean
                get() = true
        }

        val emptyPolicy = Policy.builder().build()
        val arch = io.mazewall.core.Arch.current()
        val compiledSandbox = emptyPolicy.definition.compile(arch)

        val exception = assertFailsWith<UnsupportedOperationException> {
            dummyEngine.installOnProcess(compiledSandbox)
        }

        assertEquals("Global process containment is not supported by this engine.", exception.message)
    }

    @Test
    fun `TSYNC failure text does not blame sibling no_new_privs`() {
        val eacces = PureJavaBpfEngine.tsyncFailureDetail(13, null)
        assertTrue(eacces.contains("calling thread"))
        assertTrue(eacces.contains("divergent"))
        assertTrue(eacces.contains("do not each need no_new_privs"))
        val tid = PureJavaBpfEngine.tsyncFailureDetail(null, 42L)
        assertTrue(tid.contains("tid=42"))
    }
}
