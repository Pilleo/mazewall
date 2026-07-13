package io.mazewall.enforcer

import io.mazewall.BaseIntegrationTest
import io.mazewall.EnabledIfLinuxAndSupported
import io.mazewall.Policy
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import kotlin.test.assertTrue

class SyscallFloorIntegrationTest : BaseIntegrationTest() {

    @Test
    @EnabledIfLinuxAndSupported
    fun `test that clock_nanosleep is allowed by default`() {
        val policy = Policy.builder()
            .defaultAction(SeccompAction.ACT_ERRNO)
            .build()

        val rawExecutor = Executors.newSingleThreadExecutor()
        val containedExecutor = ContainedExecutors.wrap(rawExecutor, policy)

        try {
            val result = containedExecutor.submit<Boolean> {
                Thread.sleep(1)
                true
            }.get()
            assertTrue(result)
        } finally {
            rawExecutor.shutdown()
        }
    }
}
