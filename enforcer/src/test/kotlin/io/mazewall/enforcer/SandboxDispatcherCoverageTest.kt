package io.mazewall.enforcer

import io.mazewall.Policy
import io.mazewall.Platform
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import java.util.concurrent.Callable
import kotlin.test.assertEquals
import io.mazewall.enforcer.api.SandboxDispatcher
import kotlin.test.assertNotNull

@Suppress("DEPRECATION")
class SandboxDispatcherCoverageTest {

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
    fun testShutdownAll() {
        SandboxDispatcher.getOrCreateElasticPool(Policy.builder().build().definition)
        SandboxDispatcher.shutdownAll()
    }

    @Test
    fun `legacy package SandboxDispatcher forwards execute and shutdownAll`() {
        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
        val policy = Policy.builder().build()
        val result = io.mazewall.enforcer.SandboxDispatcher.execute(policy, Callable { "legacy" })
        assertEquals("legacy", result)
        io.mazewall.enforcer.SandboxDispatcher.shutdownAll()
    }

    @Test
    fun `cache is capped and evicts least recently used executors calling shutdown`() {
        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
        SandboxDispatcher.shutdownAll()
        assertEquals(0, SandboxDispatcher.entryCount())

        val cap = 32
        val overCap = 40
        val evictedPools = mutableListOf<java.util.concurrent.ExecutorService>()

        for (i in 0 until overCap) {
            val policy = Policy.builder().apply {
                if (i % 2 == 0) {
                    allowMmapExec()
                }
            }.build().let { p ->
                io.mazewall.Policy<io.mazewall.PolicyScope, io.mazewall.PolicyState.Uncompiled>(
                    definition = p.definition.copy(defaultAction = io.mazewall.core.SeccompAction.ACT_TRACE(i))
                )
            }

            val pool = SandboxDispatcher.getOrCreateElasticPool(policy.definition)
            if (i < overCap - cap) {
                evictedPools.add(pool)
            }
        }

        // We added `overCap` distinct projections. The cache cap is `cap`.
        assertEquals(cap, SandboxDispatcher.entryCount())

        // The first `overCap - cap` pools should have been evicted and shut down.
        // Let's verify that some pools were shut down.
        // Wait briefly for shutdown to propagate if needed (though shutdown is immediate, isShutdown should be true right away).
        for (pool in evictedPools) {
            assertEquals(true, pool.isShutdown, "Evicted pool should be shut down")
        }

        SandboxDispatcher.shutdownAll()
    }

    @Test
    fun `cache keys include landlock paths to maintain distinct execution isolation`() {
        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
        SandboxDispatcher.shutdownAll()

        // Create multiple policies with the same projection but different paths
        val p1 = Policy.builder().allowFsRead("/tmp/1").build()
        val p2 = Policy.builder().allowFsRead("/tmp/2").build()
        val p3 = Policy.builder().allowFsRead("/tmp/3").build()

        SandboxDispatcher.getOrCreateElasticPool(p1.definition)
        SandboxDispatcher.getOrCreateElasticPool(p2.definition)
        SandboxDispatcher.getOrCreateElasticPool(p3.definition)

        // Executor Cache must include Landlock paths, so we expect 3 distinct entries.
        assertEquals(3, SandboxDispatcher.entryCount())
        SandboxDispatcher.shutdownAll()
    }

    @Test
    fun `tasks correctly execute and assert their distinct path policies`() {
        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
        SandboxDispatcher.shutdownAll()

        val p1 = Policy.builder().allowFsRead("/tmp/a").build()
        val p2 = Policy.builder().allowFsRead("/tmp/b").build()

        // We execute a task with p1
        val result1 = SandboxDispatcher.execute(p1, Callable { "task1" })
        assertEquals("task1", result1)

        // We execute a task with p2
        val result2 = SandboxDispatcher.execute(p2, Callable { "task2" })
        assertEquals("task2", result2)

        // Because paths are distinct, we should have two distinct executors allocated and cached
        assertEquals(2, SandboxDispatcher.entryCount())

        SandboxDispatcher.shutdownAll()
    }
}
