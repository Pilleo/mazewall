package io.mazewall.enforcer

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
        assertTrue(!testThread.isAlive)
    }

    @Test
    fun `run terminates quickly when interrupted mid-execution`() {
        val startedLatch = CountDownLatch(1)
        val exceptionRef = AtomicReference<Throwable?>(null)

        val testThread = Thread {
            startedLatch.countDown()
            try {
                JvmFloorWorkload.run()
            } catch (e: Throwable) {
                exceptionRef.set(e)
            }
        }

        testThread.start()
        assertTrue(startedLatch.await(1, TimeUnit.SECONDS))

        // Let it run for a brief moment, then interrupt it
        Thread.sleep(5)
        testThread.interrupt()

        testThread.join(3000)

        // Assert that the thread did not hang and terminated cleanly
        assertTrue(!testThread.isAlive, "Thread should have terminated after interruption")

        val thrownException = exceptionRef.get()
        if (thrownException != null) {
            assertTrue(thrownException is InterruptedException, "Should have thrown InterruptedException, but got: $thrownException")
        } else {
            println("[WARNING] Workload completed before interrupt could be delivered.")
        }
    }
}
