package io.mazewall.enforcer.api

import io.mazewall.Policy
import io.mazewall.PolicyScope
import io.mazewall.Uncompiled
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

class ContainedExecutorTest {
    @Test
    fun `typed view retains the policy scope and policy identity`() {
        val policy: Policy<PolicyScope.ProcessWideSafe, Uncompiled> = Policy.NO_EXEC
        val delegate = Executors.newSingleThreadExecutor()
        try {
            val executor: ContainedExecutor<PolicyScope.ProcessWideSafe> = ContainedExecutors.wrapTyped(delegate, policy)
            assertSame(policy, executor.policy)
        } finally {
            delegate.shutdownNow()
        }
    }
}
