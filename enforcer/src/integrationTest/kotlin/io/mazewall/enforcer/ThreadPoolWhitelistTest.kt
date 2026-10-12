package io.mazewall.enforcer

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.BaseIntegrationTest
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.state.ContainmentStateRegistry
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
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThreadPoolWhitelistTest : BaseIntegrationTest() {
    @Test
    fun `thread pool whitelist execution exhaustion`() {
        val executor = Executors.newSingleThreadExecutor()

        val policy = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .defaultAction(SeccompAction.ACT_LOG)
            .allow(Syscall.READ, Syscall.WRITE)
            .build()

        try {
        for (i in 1..35) {
            assertEquals(
                1,
                executor
                    .submit<Int> {
                ContainedExecutors.installOnCurrentThread(policy).use { receipt ->
                    receipt.requireInstalled()
                    ContainmentStateRegistry.threadState.filterDepth
                }
            }.get(10, TimeUnit.SECONDS),
            )
        }

        // ACT_LOG still allows omitted calls; this original policy adds no new restriction.
        val tighterPolicy = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .defaultAction(SeccompAction.ACT_LOG)
            .allow(Syscall.READ)
            .build()

        assertEquals(
            1,
            executor
                .submit<Int> {
            // The original nested wrappers installed the outer policy before the inner one.
            ContainedExecutors.installOnCurrentThread(policy).use { outer ->
                outer.requireInstalled()
                ContainedExecutors.installOnCurrentThread(tighterPolicy).use { inner ->
                    inner.requireInstalled()
                    ContainmentStateRegistry.threadState.filterDepth
                }
            }
        }.get(10, TimeUnit.SECONDS),
        )
        } finally {
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
