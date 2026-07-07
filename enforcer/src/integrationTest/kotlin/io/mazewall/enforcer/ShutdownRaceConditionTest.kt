package io.mazewall.enforcer

import io.mazewall.BaseIntegrationTest
import io.mazewall.IsolatedProcessTester
import io.mazewall.Policy
import io.mazewall.core.Syscall
import io.mazewall.enforcer.supervisor.SupervisorInstaller
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class ShutdownRaceConditionTest : BaseIntegrationTest() {

    fun testRegistrySyncOnInterruption() {
        val policy = Policy.builder().block(Syscall.OPEN).build()
        val executor = Executors.newFixedThreadPool(1)

        val filterDepthAfterInterruption = AtomicInteger(-1)
        val error = AtomicReference<Throwable?>(null)

        executor.execute {
            try {
                ContainedExecutors.installOnCurrentThread(policy)
            } catch (e: Exception) {
                // Expected to be interrupted
            } catch (t: Throwable) {
                error.set(t)
            } finally {
                filterDepthAfterInterruption.set(ThreadStateRegistry.state.filterDepth)
            }
        }

        // Give it a tiny bit of time to reach the handshake
        Thread.sleep(100)
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        if (error.get() != null) {
            throw error.get()!!
        }

        // With the fix, even if interrupted during handshake,
        // the filterDepth should be 1 because it's updated as soon as the filter is applied.
        assertEquals(1, filterDepthAfterInterruption.get(), "Filter depth should be 1 even after interruption")
    }

    @Test
    fun `test registry sync on interruption`() {
        IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testRegistrySyncOnInterruption")
    }
}
