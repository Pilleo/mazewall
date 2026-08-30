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
        // Enforce WARN_AND_BYPASS so that the task completes regardless of the CI kernel
        System.setProperty("io.mazewall.fallback", "WARN_AND_BYPASS")
        SandboxDispatcher.shutdownAll()

        // Let's just create temporary files to enforce tests that run with strict
        // containment paths without mock engines since they are test-class private
        val tempDirA = java.nio.file.Files.createTempDirectory("mazewall-test-a").toFile()
        val tempDirB = java.nio.file.Files.createTempDirectory("mazewall-test-b").toFile()
        try {
            val fileA = java.io.File(tempDirA, "fileA.txt")
            val fileB = java.io.File(tempDirB, "fileB.txt")
            fileA.writeText("dataA")
            fileB.writeText("dataB")

            val p1 = Policy.builder()
                .allowFsRead(tempDirA.absolutePath)
                .build()

            val p2 = Policy.builder()
                .allowFsRead(tempDirB.absolutePath)
                .build()

            // We execute a task with p1
            val resultA = SandboxDispatcher.execute(p1, Callable {
                // Reading allowed path should succeed
                val text = fileA.readText()
                // In bypass mode, both reads succeed because the kernel is bypassed,
                // but the point of the test is that these policies create distinct CacheKeys.
                text to fileB.readText()
            })
            assertEquals("dataA", resultA.first)
            assertEquals("dataB", resultA.second)

            // We execute a task with p2
            val resultB = SandboxDispatcher.execute(p2, Callable {
                val text = fileB.readText()
                text to fileA.readText()
            })
            assertEquals("dataB", resultB.first)
            assertEquals("dataA", resultB.second)

            // Verify they execute on different pooled instances since their paths differ
            assertEquals(2, SandboxDispatcher.entryCount())

        } finally {
            tempDirA.deleteRecursively()
            tempDirB.deleteRecursively()
            System.clearProperty("io.mazewall.fallback")
            SandboxDispatcher.shutdownAll()
        }
    }
}
