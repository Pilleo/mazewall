package io.mazewall.enforcer

import io.mazewall.Policy
import io.mazewall.Platform
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SandboxDispatcherCoverageTest {

    @Test
    fun testExecute() {
        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
        try {
            val policy = Policy.builder().build()
            val result = SandboxDispatcher.execute(policy, Callable { "success" })
            assertEquals("success", result)
        } finally {
            System.clearProperty("io.mazewall.fallback")
        }
    }

    @Test
    fun testExecuteBlock() {
        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
        try {
            val policy = Policy.builder().build()
            val result = SandboxDispatcher.executeBlock(policy) { "success" }
            assertEquals("success", result)
        } finally {
            System.clearProperty("io.mazewall.fallback")
        }
    }

    @Test
    fun testShutdownAll() {
        SandboxDispatcher.getOrCreateElasticPool(Policy.builder().build().definition)
        SandboxDispatcher.shutdownAll()
    }
}
