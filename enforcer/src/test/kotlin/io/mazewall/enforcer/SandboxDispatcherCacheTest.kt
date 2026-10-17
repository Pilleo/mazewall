package io.mazewall.enforcer

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.SandboxDispatcher
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SandboxDispatcherCacheTest {
    @AfterEach
    fun tearDown() {
        SandboxDispatcher.shutdownAll()
        System.clearProperty("io.mazewall.fallback")
    }

    @Test
    fun testExecute() {
        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build()
        val result = SandboxDispatcher.execute(policy, Callable { "success" })
        assertEquals("success", result)
    }

    @Test
    fun testExecuteBlock() {
        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build()
        val result = SandboxDispatcher.executeBlock(policy) { "success" }
        assertEquals("success", result)
    }

    @Test
    fun `shutdownAll terminates cached pools`() {
        val pool = SandboxDispatcher.getOrCreateElasticPool(ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build().definition)
        SandboxDispatcher.shutdownAll()
        assertTrue(pool.isShutdown)
    }

    @Test
    fun `close is AutoCloseable shutdown`() {
        val pool = SandboxDispatcher.getOrCreateElasticPool(ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build().definition)
        (SandboxDispatcher as AutoCloseable).close()
        assertTrue(pool.isShutdown)
        assertTrue(pool.isTerminated)
    }

    @Test
    fun `evicts least recently used pools above the fixed cache cap`() {
        val firstPool = SandboxDispatcher.getOrCreateElasticPool(
            ProcessPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .customViolationPhrase("policy-0")
                .build()
                .definition,
        )

        repeat(SandboxDispatcher.MAX_CACHED_POOLS) { index ->
            SandboxDispatcher.getOrCreateElasticPool(
                ProcessPolicyBuilder
                    .denyList(RuntimeProfile.NATIVE_IMAGE)
                    .defaultAction(
                        io.mazewall.platform.syscall.SeccompAction
                        .ACT_TRACE(index + 1),
                    ).build()
                    .definition,
            )
        }

        assertEquals(SandboxDispatcher.MAX_CACHED_POOLS, SandboxDispatcher.cachedPoolsForTest().size)
        assertTrue(firstPool.isShutdown, "the least recently used pool must be shut down before eviction")
        assertTrue(firstPool.isTerminated, "evicted pools must finish terminating")
    }
}
