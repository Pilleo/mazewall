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
            // Create a distinct policy by having a different projection (e.g., different allowMmapExec flag if we could,
            // but we'll use a different syscall action to ensure a distinct CacheKey projection).
            val policy = Policy.builder().apply {
                if (i % 2 == 0) {
                    allowMmapExec()
                }
                // We'll map different trace IDs to a syscall to ensure a distinct CacheKey projection.
                // Policy.Builder.block expects a Syscall and optionally a SeccompAction,
                // however block(Syscall) defaults to ERRNO. To set trace, we'll configure a specific action
                // if there is a method for it, or use defaultAction if possible. Wait, PolicyBuilder.block accepts
                // vararg pairs maybe? Wait, no, block(syscall) or block(syscall, action).
                // But wait, there is no block(syscall, action) natively? Let's check the API.
                // Wait, ACT_TRACE is valid. Let's just use `block(Syscall, SeccompAction)` if it exists.
                // Ah, the error is: `Argument type mismatch: actual type is 'SeccompAction.ACT_TRACE', but 'Syscall' was expected.`
                // This means `block` takes vararg `Syscall`?
                // Let's use `defaultAction(io.mazewall.core.SeccompAction.ACT_TRACE(i))` if available,
                // or just `policyDefinition` manipulation if we can't.
                // Let's look at `allow` or `block`.
            }.build().let { p ->
                // Alternatively, directly copy the definition if we can't use builder for arbitrary actions.
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
    fun `cache keys ignore landlock paths to prevent leaks`() {
        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
        SandboxDispatcher.shutdownAll()

        // Create multiple policies with the same projection but different paths
        val p1 = Policy.builder().allowFsRead("/tmp/1").build()
        val p2 = Policy.builder().allowFsRead("/tmp/2").build()
        val p3 = Policy.builder().allowFsRead("/tmp/3").build()

        SandboxDispatcher.getOrCreateElasticPool(p1.definition)
        SandboxDispatcher.getOrCreateElasticPool(p2.definition)
        SandboxDispatcher.getOrCreateElasticPool(p3.definition)

        // All should map to the same CacheKey, so only 1 entry should exist
        assertEquals(1, SandboxDispatcher.entryCount())
        SandboxDispatcher.shutdownAll()
    }
}
