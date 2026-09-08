package io.mazewall.enforcer

import io.mazewall.Policy
import io.mazewall.enforcer.api.SandboxDispatcher
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SandboxDispatcherCacheTest {
    @AfterEach
    fun tearDown() {
        System.clearProperty("io.mazewall.fallback")
    }

    @Test
    fun testExecute() {
        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
        val policy = Policy.builder().build()
        val result = SandboxDispatcher.execute(policy, Callable { "success" })
        assertEquals("success", result)
    }

    @Test
    fun testExecuteBlock() {
        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
        val policy = Policy.builder().build()
        val result = SandboxDispatcher.executeBlock(policy) { "success" }
        assertEquals("success", result)
    }

    @Test
    fun `shutdownAll terminates cached pools`() {
        val pool = SandboxDispatcher.getOrCreateElasticPool(Policy.builder().build().definition)
        SandboxDispatcher.shutdownAll()
        assertTrue(pool.isShutdown)
    }

    @Test
    fun `evicts least recently used pools above the fixed cache cap`() {
        val firstPool = SandboxDispatcher.getOrCreateElasticPool(
            Policy
                .builder()
                .customViolationPhrase("policy-0")
                .build()
                .definition,
        )

        repeat(SandboxDispatcher.MAX_CACHED_POOLS) { index ->
            SandboxDispatcher.getOrCreateElasticPool(
                Policy
                    .builder()
                    .defaultAction(
                        io.mazewall.core.SeccompAction
                        .ACT_TRACE(index + 1),
                    ).build()
                    .definition,
            )
        }

        assertEquals(SandboxDispatcher.MAX_CACHED_POOLS, SandboxDispatcher.cachedPoolsForTest().size)
        assertTrue(firstPool.isShutdown, "the least recently used pool must be shut down before eviction")
    }

    @Test
    fun `legacy package SandboxDispatcher forwards execute and shutdownAll`() {
        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
        val policy = Policy.builder().build()
        val result = io.mazewall.enforcer.SandboxDispatcher
            .execute(policy, Callable { "legacy" })
        assertEquals("legacy", result)
        io.mazewall.enforcer.SandboxDispatcher
            .shutdownAll()
    }
}
