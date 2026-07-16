package io.mazewall.enforcer

import io.mazewall.Policy
import io.mazewall.Platform
import io.mazewall.LinuxNative
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SandboxDispatcherCoverageTest {

    @AfterEach
    fun tearDown() {
        System.clearProperty("io.mazewall.fallback")
        LinuxNative.resetToDefault()
        Platform.resetToDefault()
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
}
