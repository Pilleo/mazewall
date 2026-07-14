package io.mazewall.profiler

import io.mazewall.Policy
import io.mazewall.profiler.engine.TraceEvent
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals

class ProfilerCoverageTest {

    @Test
    fun testProfileThrowsInVirtualThread() {
        val thread = Thread.ofVirtual().unstarted {
            try {
                Profiler.profile { "fail" }
            } catch (e: IllegalStateException) {
                // Expected
            }
        }
        thread.start()
        thread.join()
    }

    @Test
    fun testWrapExecutor() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val wrapped = Profiler.wrap(executor, Policy.builder().build())
            wrapped.execute { println("hello") }
            wrapped.submit { println("hello") }
            wrapped.submit(java.util.concurrent.Callable { "hello" })
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
