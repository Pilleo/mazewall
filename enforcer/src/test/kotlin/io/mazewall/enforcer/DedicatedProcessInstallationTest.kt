package io.mazewall.enforcer

import io.mazewall.*
import io.mazewall.enforcer.api.ContainedExecutors
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DedicatedProcessInstallationTest {
    @Test
    fun `dedicated worker rejects installation from virtual thread`() {
        val outcome = java.util.concurrent.CompletableFuture<Throwable>()
        val thread = Thread.ofVirtual().start {
            try {
                ContainedExecutors.installOnDedicatedProcess(ProcessPolicies.denyNetwork(RuntimeProfile.HOTSPOT_JIT))
                outcome.complete(AssertionError("virtual-thread installation unexpectedly succeeded"))
            } catch (failure: Throwable) {
                outcome.complete(failure)
            }
        }
        val failure = outcome.get(5, java.util.concurrent.TimeUnit.SECONDS)
        thread.join(5_000)
        assertFalse(thread.isAlive)
        assertInstanceOf(IllegalStateException::class.java, failure)
        assertTrue(failure.message.orEmpty().contains("virtual", ignoreCase = true))
    }

    @AfterEach
    fun reset() {
        Platform.resetToDefault()
        System.clearProperty("io.mazewall.fallback")
    }

    @Test
    fun `dedicated worker cannot accept thread-only Landlock without TSYNC`() {
        Platform.setProvider(object : PlatformProvider by RealPlatformProvider {
            override fun getLandlockAbiVersion(): Int = 7

            override fun getLandlockCapability(): KernelCapability<Int> = KernelCapability.Available(7)
        })
        val failure = assertThrows(IllegalStateException::class.java) {
            ContainedExecutors.installOnDedicatedProcess(ProcessPolicies.workerFilesystem(RuntimeProfile.HOTSPOT_JIT))
        }
        assertTrue(failure.message.orEmpty().contains("TSYNC"))
    }

    @Test
    fun `dedicated worker rejects fallback before installing anything`() {
        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
        val failure = assertThrows(IllegalStateException::class.java) {
            ContainedExecutors.installOnDedicatedProcess(ProcessPolicies.denyNetwork(RuntimeProfile.HOTSPOT_JIT))
        }
        assertTrue(failure.message.orEmpty().contains("fallback"))
    }
}
