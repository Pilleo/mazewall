package io.mazewall.seccomp

import io.mazewall.Policy
import io.mazewall.compile
import io.mazewall.core.Arch
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.lang.foreign.MemorySegment
import kotlin.test.assertFailsWith

class PureJavaBpfEngineBypassTest {
    @Test
    fun `direct state machine calls throw IllegalStateException from virtual thread`() {
        val virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor()

        virtualThreadExecutor.submit {
            val built = SeccompInstallationState.FilterBuilt(MemorySegment.NULL)
            assertFailsWith<IllegalStateException> {
                built.lockPrivileges()
            }
        }.get()
    }

    @Test
    fun `PureJavaBpfEngine install still throws IllegalStateException from virtual thread`() {
        val policy = Policy.PURE_COMPUTE_UNSAFE.definition.compile(Arch.current())
        val virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor()

        virtualThreadExecutor.submit {
            assertFailsWith<IllegalStateException> {
                PureJavaBpfEngine.install(policy)
            }
        }.get()
    }
}
