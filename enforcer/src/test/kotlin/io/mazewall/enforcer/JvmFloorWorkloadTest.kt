package io.mazewall.enforcer

import io.mazewall.enforcer.engine.JvmFloorWorkload
import io.mazewall.testing.awaitCondition
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class JvmFloorWorkloadTest {
    @Test
    fun `run immediately throws InterruptedException if thread is already interrupted`() {
        val testThread = Thread {
            Thread.currentThread().interrupt()
            try {
                JvmFloorWorkload.run()
            } catch (e: InterruptedException) {
                // Restore interrupt flag so assertThrows or subsequent checks can see it if needed
                Thread.currentThread().interrupt()
                throw e
            }
        }
        testThread.start()
        testThread.join(2000)

        // The thread should have terminated quickly
        assertTrue(!testThread.isAlive, "pre-interrupted workload terminated")
    }

    @Test
    fun `run terminates quickly when interrupted mid-execution`() {
        val startedLatch = CountDownLatch(1)
        val releaseWorkload = CompletableFuture<Unit>()
        val exceptionRef = AtomicReference<Throwable?>(null)

        val testThread = Thread {
            try {
                JvmFloorWorkload.run {
                    startedLatch.countDown()
                    try {
                        // join preserves the interrupt until the real workload checks it.
                        releaseWorkload.orTimeout(5, TimeUnit.SECONDS).join()
                    } catch (failure: CompletionException) {
                        throw AssertionError("Timed out waiting for test to release the started workload", failure)
                    }
                }
            } catch (e: Throwable) {
                exceptionRef.set(e)
            }
        }

        testThread.start()
        try {
            assertTrue(startedLatch.await(5, TimeUnit.SECONDS), "workload entered run before interruption")
            testThread.interrupt()
        } finally {
            releaseWorkload.complete(Unit)
        }
        awaitCondition(description = "interrupted JVM floor workload terminated") { !testThread.isAlive }

        // Assert that the thread did not hang and terminated cleanly
        assertTrue(!testThread.isAlive, "Thread should have terminated after interruption")

        val thrownException = exceptionRef.get()
        assertTrue(thrownException is InterruptedException, "Should have thrown InterruptedException, but got: $thrownException")
    }
}
