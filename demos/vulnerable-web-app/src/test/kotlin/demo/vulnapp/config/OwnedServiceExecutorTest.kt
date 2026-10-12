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
    fun `concurrent drain includes lifecycle lock wait in its budget`() {
        val entered = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val raw = Executors.newSingleThreadExecutor()
        val observable = object : java.util.concurrent.ExecutorService by raw {
            override fun awaitTermination(
                timeout: Long,
                unit: java.util.concurrent.TimeUnit,
            ): Boolean {
                waiting.countDown()
                return raw.awaitTermination(timeout, unit)
            }
        }
        val owner = ServiceExecutorOwner(Duration.ofMillis(180))
        owner.ownProfiling(observable).execute {
            entered.countDown()
            while (release.count > 0) {
                try {
                    release.await()
                } catch (_: InterruptedException) {
                    // Hold the actual body through shutdown cancellation.
                }
            }
        }
        val first = Thread { owner.stopAdmissionAndDrain() }
        try {
            assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS))
            first.start()
            assertTrue(waiting.await(1, java.util.concurrent.TimeUnit.SECONDS))
            val started = System.nanoTime()
            assertIs<ExecutorDrain.Incomplete>(owner.stopAdmissionAndDrain())
            assertTrue(
                System.nanoTime() - started < java.util.concurrent.TimeUnit.MILLISECONDS
                    .toNanos(280),
                "a concurrent retry must not receive a fresh budget after waiting for the first drain",
            )
        } finally {
            release.countDown()
            first.join(2_000)
            raw.shutdownNow()
            raw.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)
            owner.stopAdmissionAndDrain()
        }
    }

    @Test
    fun `incomplete drain retains the owner and can be retried after physical worker exit`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delegate = Executors.newSingleThreadExecutor()
        val owner = ServiceExecutorOwner(Duration.ofMillis(20))
        owner.ownProfiling(delegate).execute {
            started.countDown()
            while (release.count > 0) {
                try {
                    release.await()
                } catch (_: InterruptedException) {
                    // This workload deliberately does not cooperate with cancellation.
                }
            }
        }
        try {
            assertTrue(started.await(1, java.util.concurrent.TimeUnit.SECONDS))
            assertIs<ExecutorDrain.Incomplete>(owner.stopAdmissionAndDrain())
            release.countDown()
            assertTrue(delegate.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS))
            assertIs<ExecutorDrain.Complete>(owner.stopAdmissionAndDrain())
        } finally {
            release.countDown()
            delegate.shutdownNow()
            delegate.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    @Test
    fun `owner stops admission and drains accepted work`() {
        val owner = ServiceExecutorOwner(Duration.ofSeconds(1))
        val executor = owner.ownProfiling(Executors.newSingleThreadExecutor())

        assertEquals("ok", executor.call { "ok" })
        assertIs<ExecutorDrain.Complete>(owner.stopAdmissionAndDrain())
        assertFailsWith<RejectedExecutionException> { executor.call { "late" } }
    }

    @Test
    fun `profiling timeout is explicit and requests worker interruption`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val owner = ServiceExecutorOwner(Duration.ofMillis(20))
        val executor = owner.ownProfiling(Executors.newSingleThreadExecutor())
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
        owner.stopAdmissionAndDrain()
    }

    @Test
    fun `interrupted drain is incomplete and preserves interruption`() {
        val release = CountDownLatch(1)
        val owner = ServiceExecutorOwner(Duration.ofSeconds(1))
        owner.ownProfiling(Executors.newSingleThreadExecutor()).execute { release.await() }
        Thread.currentThread().interrupt()
        try {
            assertIs<ExecutorDrain.Incomplete.Interrupted>(owner.stopAdmissionAndDrain())
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            release.countDown()
            owner.stopAdmissionAndDrain()
        }
    }

    @Test
    fun `interrupted caller cancels accepted work and preserves interruption`() {
        val started = CountDownLatch(1)
        val workerInterrupted = CountDownLatch(1)
        val owner = ServiceExecutorOwner(Duration.ofSeconds(1))
        val executor = owner.ownProfiling(Executors.newSingleThreadExecutor())
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
