package io.mazewall.profiler

import io.mazewall.EnabledIfLinuxAndSupported
import io.mazewall.Policy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.Callable

@EnabledIfLinuxAndSupported
class ProfilerExecutorWrapperTest {

    @Test
    fun `test wrapper submit callable`() {
        val delegate = Executors.newSingleThreadExecutor()
        val wrapper = Profiler.wrap(delegate, Policy.PURE_COMPUTE_UNSAFE)

        val future = wrapper.submit(Callable {
            "result"
        })

        assertEquals("result", future.get())

        val bob = wrapper.compileBillOfBehavior()
        assertNotNull(bob)

        wrapper.shutdown()
        wrapper.shutdownNow()
    }

    @Test
    fun `test wrapper execute runnable`() {
        val delegate = Executors.newSingleThreadExecutor()
        val wrapper = Profiler.wrap(delegate, Policy.PURE_COMPUTE_UNSAFE)

        var executed = false
        wrapper.execute(Runnable {
            executed = true
        })

        Thread.sleep(100) // allow execution
        assertTrue(executed)

        val bob = wrapper.compileBillOfBehavior()
        assertNotNull(bob)

        wrapper.shutdown()
        wrapper.shutdownNow()
    }

    @Test
    fun `test wrapper submit callable without stacktraces`() {
        val delegate = Executors.newSingleThreadExecutor()
        val wrapper = Profiler.wrap(delegate, false, Policy.PURE_COMPUTE_UNSAFE)

        val future = wrapper.submit(Callable {
            "result"
        })

        assertEquals("result", future.get())

        val bob = wrapper.compileBillOfBehavior()
        assertNotNull(bob)

        wrapper.shutdown()
        wrapper.shutdownNow()
    }
}
