package io.mazewall.enforcer

import io.mazewall.BaseIntegrationTest
import io.mazewall.IsolatedProcessTester
import io.mazewall.Policy
import io.mazewall.core.Syscall
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.testing.awaitOrFail
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShutdownRaceConditionTest : BaseIntegrationTest() {
    fun testRegistrySyncOnInterruption() {
        val policy = Policy
            .builder()
            .block(Syscall.OPEN)
            .allowMmapExec()
            .build()
        val executor = Executors.newFixedThreadPool(1)

        val filterDepthAfterInterruption = AtomicInteger(-1)
        val error = AtomicReference<Throwable?>(null)
        val installed = CountDownLatch(1)
        val holdWorker = CountDownLatch(1)

        executor.execute {
            try {
                ContainmentStateRegistry.threadState = ContainerState()
                ContainedExecutors.installOnCurrentThread(policy)
                installed.countDown()
                holdWorker.awaitOrFail("installed worker interrupted during shutdown")
            } catch (e: Exception) {
                // Expected to be interrupted
            } catch (t: Throwable) {
                error.set(t)
            } finally {
                filterDepthAfterInterruption.set(ContainmentStateRegistry.threadState.filterDepth)
            }
        }

        try {
            installed.awaitOrFail("thread filter installation completed")
        } finally {
            executor.shutdownNow()
            holdWorker.countDown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "interrupted containment worker terminated")
        }

        if (error.get() != null) {
            throw error.get()!!
        }

        assertEquals(1, filterDepthAfterInterruption.get(), "Filter depth should be 1 even after interruption")
    }

    @Test
    fun `test registry sync on interruption`() {
        IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testRegistrySyncOnInterruption")
    }
}
