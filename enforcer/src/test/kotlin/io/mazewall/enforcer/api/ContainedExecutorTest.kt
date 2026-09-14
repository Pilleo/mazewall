package io.mazewall.enforcer.api

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.PolicyScope
import io.mazewall.enforcer.api.Uncompiled
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
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
