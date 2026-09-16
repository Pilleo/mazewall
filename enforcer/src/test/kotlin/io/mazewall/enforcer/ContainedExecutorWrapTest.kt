package io.mazewall.enforcer.api

import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ContainedExecutorWrapTest {
    @Test
    fun `wrap returns thread-local contained executor not a process sandbox`() {
        Executors.newSingleThreadExecutor().use { raw ->
            val wrapped = ContainedExecutors.wrap(raw, Policy.PURE_COMPUTE)
            assertIs<ContainedExecutor<PolicyScope.ThreadLocalOnly>>(wrapped)
            assertTrue(wrapped.policy.definition === Policy.PURE_COMPUTE.definition || wrapped.policy.isSyscallAllowed(io.mazewall.platform.syscall.Syscall.READ))
            val asService: java.util.concurrent.ExecutorService = wrapped
            assertTrue(asService === wrapped)
        }
    }
}
