package io.mazewall.profiler

import io.mazewall.EnabledIfLinuxAndSupported
import io.mazewall.Policy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

@EnabledIfLinuxAndSupported
class ProfilerTest {

    @Test
    fun `test wrap ExecutorService`() {
        val delegate = Executors.newSingleThreadExecutor()
        val wrapper = Profiler.wrap(delegate, Policy.PURE_COMPUTE_UNSAFE)

        var executed = false
        val future = wrapper.submit(Runnable {
            executed = true
        })

        future.get()
        assertTrue(executed)

        val bob = wrapper.compileBillOfBehavior()
        assertNotNull(bob)

        wrapper.shutdown()
        wrapper.shutdownNow()
    }

    @Test
    fun `test wrap without stacktraces`() {
        val delegate = Executors.newSingleThreadExecutor()
        val wrapper = Profiler.wrap(delegate, false, Policy.PURE_COMPUTE_UNSAFE)

        var executed = false
        val future = wrapper.submit(Runnable {
            executed = true
        })

        future.get()
        assertTrue(executed)

        val bob = wrapper.compileBillOfBehavior()
        assertNotNull(bob)

        wrapper.shutdown()
        wrapper.shutdownNow()
    }

    @Test
    fun `test profile block`() {
        val bob = Profiler.profile {
            "result"
        }
        assertNotNull(bob)
        assertEquals("result", bob.value)
        assertFalse(bob.behavior.syscalls.isEmpty())
    }

    @Test
    fun `test profile block process wide`() {
        val bob = Profiler.profile(processWide = true) {
            "result"
        }
        assertNotNull(bob)
        assertEquals("result", bob.value)
    }

    @Test
    fun `test profiler shutdown`() {
        Profiler.shutdown()
    }
}
