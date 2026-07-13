package io.mazewall.enforcer

import io.mazewall.BaseIntegrationTest
import io.mazewall.EnabledIfLinuxAndSupported
import io.mazewall.Policy
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import kotlin.test.assertTrue

class JvmFloorIntegrationTest : BaseIntegrationTest() {

    @Test
    @EnabledIfLinuxAndSupported
    fun `jvm floor workload runs successfully under restrictive default policy`() {
        val policy = Policy.builder()
            .defaultAction(SeccompAction.ACT_ERRNO)
            .allow(Syscall.SOCKET)
            .allow(Syscall.CONNECT)
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
