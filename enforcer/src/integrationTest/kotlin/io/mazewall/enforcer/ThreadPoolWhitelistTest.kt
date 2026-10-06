package io.mazewall.enforcer

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.BaseIntegrationTest
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.Policy
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

class ThreadPoolWhitelistTest : BaseIntegrationTest() {
    @Test
    fun `thread pool whitelist execution exhaustion`() {
        val executor = Executors.newSingleThreadExecutor()

        val policy = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .defaultAction(SeccompAction.ACT_LOG)
            .allow(Syscall.READ, Syscall.WRITE)
            .build()

        val wrapped = ContainedExecutors.wrap(executor, policy)

        for (i in 1..35) {
            wrapped
                .submit {
                // Do nothing
            }.get()
        }

        // Let's also verify that tightening the whitelist adds a new filter
        val tighterPolicy = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .defaultAction(SeccompAction.ACT_LOG)
            .allow(Syscall.READ)
            .build()

        val wrapped2 = ContainedExecutors.wrap(wrapped, tighterPolicy)
        wrapped2.submit {}.get()

        wrapped2.shutdown()
    }
}
