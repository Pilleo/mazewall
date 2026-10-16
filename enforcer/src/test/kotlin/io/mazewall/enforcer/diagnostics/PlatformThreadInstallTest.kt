package io.mazewall.enforcer.diagnostics

import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.Policy
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlatformThreadInstallTest {
    @Test
    fun `virtual thread cannot install seccomp`() {
        val thrown = AtomicReference<Throwable>()
        Thread
            .ofVirtual()
            .name("mazewall-vt-install")
            .start {
            try {
                ContainedExecutors.installOnCurrentThread(Policy.PURE_COMPUTE)
            } catch (failure: Throwable) {
                thrown.set(failure)
            }
        }.join()
        val failure = thrown.get()
        assertIs<IllegalStateException>(failure)
        assertTrue(failure.message!!.contains("virtual thread", ignoreCase = true))
    }
}
