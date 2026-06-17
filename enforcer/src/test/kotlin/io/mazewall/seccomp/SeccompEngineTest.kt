package io.mazewall.seccomp
import io.mazewall.Policy
import io.mazewall.CompiledSandbox
import io.mazewall.compile
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
