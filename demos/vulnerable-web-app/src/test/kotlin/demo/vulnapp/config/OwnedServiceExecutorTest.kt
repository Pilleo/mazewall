package demo.vulnapp.config

import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OwnedServiceExecutorTest {
    @Test
    fun `owner stops admission and drains accepted work`() {
        val owner = ServiceExecutorOwner(Duration.ofSeconds(1))
        val executor = owner.own(Executors.newSingleThreadExecutor())

        assertEquals("ok", executor.call { "ok" })
        assertIs<ExecutorDrain.Complete>(owner.stopAdmissionAndDrain())
        assertFailsWith<RejectedExecutionException> { executor.call { "late" } }
    }

    @Test
    fun `timeout is explicit and forces worker termination`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val owner = ServiceExecutorOwner(Duration.ofMillis(20))
        val executor = owner.own(Executors.newSingleThreadExecutor())
        executor.execute {
            started.countDown()
            try {
                release.await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        started.await()

        assertIs<ExecutorDrain.Incomplete.Timeout>(owner.stopAdmissionAndDrain())
        release.countDown()
    }

    @Test
    fun `interrupted drain is incomplete and preserves interruption`() {
        val release = CountDownLatch(1)
        val owner = ServiceExecutorOwner(Duration.ofSeconds(1))
        owner.own(Executors.newSingleThreadExecutor()).execute { release.await() }
        Thread.currentThread().interrupt()
        try {
            assertIs<ExecutorDrain.Incomplete.Interrupted>(owner.stopAdmissionAndDrain())
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            release.countDown()
        }
    }

    @Test
    fun `interrupted caller cancels accepted work and preserves interruption`() {
        val started = CountDownLatch(1)
        val workerInterrupted = CountDownLatch(1)
        val owner = ServiceExecutorOwner(Duration.ofSeconds(1))
        val executor = owner.own(Executors.newSingleThreadExecutor())
        val caller = Thread.currentThread()
        val interrupter = Thread {
            started.await()
            caller.interrupt()
        }
        interrupter.start()

        try {
            assertFailsWith<InterruptedException> {
                executor.call {
                    started.countDown()
                    try {
                        CountDownLatch(1).await()
                    } catch (failure: InterruptedException) {
                        workerInterrupted.countDown()
                        throw failure
                    }
                }
            }
            assertTrue(Thread.currentThread().isInterrupted)
            Thread.interrupted()
            assertTrue(workerInterrupted.await(1, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            Thread.interrupted()
            interrupter.join()
            owner.stopAdmissionAndDrain()
        }
    }
}
