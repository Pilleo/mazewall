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
class SandboxDispatcherTest {

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
}
