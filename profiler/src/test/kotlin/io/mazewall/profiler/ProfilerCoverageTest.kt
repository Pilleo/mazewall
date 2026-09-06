package io.mazewall.profiler

import io.mazewall.Policy
import io.mazewall.profiler.engine.TraceEvent
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProfilerCoverageTest {
    @Test
    fun `profile rejects virtual threads`() {
        val failure = AtomicReference<Throwable?>()
        val thread = Thread.ofVirtual().unstarted {
            try {
                Profiler.profile { "unexpected" }
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        thread.start()
        thread.join()
        assertIs<IllegalStateException>(failure.get())
    }

    @Test
    fun `wrapped executor executes submitted tasks`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val wrapped = Profiler.wrap(executor, Policy.builder().build())
            val executed = CountDownLatch(3)
            wrapped.execute { executed.countDown() }
            wrapped.submit { executed.countDown() }.get(5, TimeUnit.SECONDS)
            wrapped.submit(java.util.concurrent.Callable { executed.countDown() }).get(5, TimeUnit.SECONDS)
            assertTrue(executed.await(5, TimeUnit.SECONDS))
            wrapped.shutdown()
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun testProfilingResult() {
        val bob = BillOfBehavior()
        val res = ProfilingResult("data", bob, ConcurrentHashMap())
        assertEquals("data", res.value)
        assertEquals(bob, res.behavior)
    }
}
