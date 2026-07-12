package io.mazewall.enforcer

import io.mazewall.BaseIntegrationTest
import io.mazewall.EnabledIfLinuxAndSupported
import io.mazewall.Policy
import io.mazewall.core.SeccompAction
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import kotlin.test.assertTrue

class JvmFloorIntegrationTest : BaseIntegrationTest() {

    /**
     * Verifies that the JVM invariant syscall floor is sufficient to run a heavy
     * workload (JIT, GC, Loom, NIO) under a restrictive default policy.
     */
    @Test
    @EnabledIfLinuxAndSupported
    fun `jvm floor workload runs successfully under restrictive default policy`() {
        // Use a policy that blocks everything by default except what's in the critical floor
        val policy = Policy.builder()
            .defaultAction(SeccompAction.ACT_ERRNO)
            .build()

        val rawExecutor = Executors.newSingleThreadExecutor()
        val containedExecutor = ContainedExecutors.wrap(rawExecutor, policy)

        try {
            val result = containedExecutor.submit<Boolean> {
                try {
                    JvmFloorWorkload.run()
                    true
                } catch (e: Throwable) {
                    e.printStackTrace()
                    false
                }
            }.get()

            assertTrue(result, "JvmFloorWorkload should complete successfully under restrictive policy")
        } finally {
            rawExecutor.shutdown()
        }
    }
}
