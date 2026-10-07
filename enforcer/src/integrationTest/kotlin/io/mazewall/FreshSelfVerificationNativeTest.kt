package io.mazewall.enforcer.api

import io.mazewall.enforcer.diagnostics.MazewallEvents
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

class FreshSelfVerificationNativeTest : BaseIntegrationTest() {
    @Test
    @EnabledIfLinuxAndSupported
    fun `identical policies are verified on each fresh native worker`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifyEachFreshWorker")
    }

    fun verifyEachFreshWorker() {
        System.setProperty("io.mazewall.selfVerify", "true")
        System.setProperty("io.mazewall.fallback", "FAIL")
        val verified = AtomicInteger()
        MazewallEvents.register { event ->
            if (event is MazewallEvents.SelfVerificationResult && event.passed) verified.incrementAndGet()
        }
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).block(io.mazewall.platform.syscall.Syscall.CONNECT).build()
        try {
            repeat(2) {
                Executors.newSingleThreadExecutor().use { worker ->
                    assertEquals(42, ContainedExecutors.wrap(worker, policy).submit(Callable { 42 }).get(10, TimeUnit.SECONDS))
                }
            }
            assertEquals(2, verified.get(), "Every actual installation must obtain its own verification evidence")
        } finally {
            MazewallEvents.clear()
            System.clearProperty("io.mazewall.selfVerify")
        }
    }
}
