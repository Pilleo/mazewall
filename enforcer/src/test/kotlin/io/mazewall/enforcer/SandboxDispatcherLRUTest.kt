package io.mazewall.enforcer

import io.mazewall.Policy
import io.mazewall.Platform
import io.mazewall.enforcer.api.SandboxDispatcher
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.nio.file.Paths
import io.mazewall.core.Syscall

@Suppress("DEPRECATION")
class SandboxDispatcherLRUTest {

    @AfterEach
    fun tearDown() {
        System.clearProperty("io.mazewall.fallback")
        SandboxDispatcher.shutdownAll()
    }

    @Test
    fun `test LRU cache bounding and eviction`() {
        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
        val executors = mutableListOf<java.util.concurrent.ExecutorService>()

        for (i in 1..35) {
            val policyBuilder = Policy.builder()

            policyBuilder.allow(Syscall.entries[i])

            val policy = policyBuilder.build()
            val exec = SandboxDispatcher.getOrCreateElasticPool(policy.definition)
            executors.add(exec)
        }

        assertEquals(32, SandboxDispatcher.getPoolCacheSize(), "Cache size should be capped at 32")

        assertTrue(executors[0].isShutdown, "First executor should be shutdown")
        assertTrue(executors[1].isShutdown, "Second executor should be shutdown")
        assertTrue(executors[2].isShutdown, "Third executor should be shutdown")

        assertTrue(!executors[34].isShutdown, "Latest executor should not be shutdown")
    }

    @Test
    fun `test Landlock paths are ignored in cache key`() {
        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")

        // Use string path version which is likely available or we can use another method to add paths.
        // wait, let's just use `allowJvmClasspath` on one and not on the other?
        // No, JVM classpath is the same. Let's look at `allowFsRead(String)` - but that didn't compile either.
        // Wait, why did the previous string allowFsRead fail?
        // "None of the following candidates is applicable:
        // fun allowFsRead(path: String): Policy.Builder<PolicyScope.ThreadLocalOnly>:
        // Argument type mismatch: actual type is 'Path!', but 'String' was expected."
        // Ah! In the previous attempt I used `Paths.get("/tmp/a")` instead of `"/tmp/a"`

        val p1 = Policy.builder().allowFsRead("/tmp/a").build()
        val p2 = Policy.builder().allowFsRead("/tmp/b").build()

        val exec1 = SandboxDispatcher.getOrCreateElasticPool(p1.definition)
        val exec2 = SandboxDispatcher.getOrCreateElasticPool(p2.definition)

        assertTrue(exec1 === exec2, "Policies with only different Landlock paths should map to the same executor")
    }
}
