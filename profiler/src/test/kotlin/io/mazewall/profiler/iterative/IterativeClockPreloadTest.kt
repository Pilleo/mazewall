package io.mazewall.profiler.iterative

import io.mazewall.EnabledIfLinuxAndSupported
import io.mazewall.IsolatedProcessTester
import io.mazewall.Policy
import org.junit.jupiter.api.Test

@EnabledIfLinuxAndSupported
class IterativeClockPreloadTest {
    @Test
    fun `clock wrappers remain usable in a restrictive fresh iteration`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "clockIteration")
    }

    fun clockIteration() {
        // Preload both native clock wrappers on the uncontained parent before creating the worker.
        System.currentTimeMillis()
        System.nanoTime()
        val failure = RealIterativeTaskExecutor().executeTask(Policy.PURE_COMPUTE_UNSAFE) {
            check(System.currentTimeMillis() > 0) { "contained wall clock returned no timestamp" }
            check(System.nanoTime() != 0L) { "contained monotonic clock returned no timestamp" }
        }
        if (failure != null) throw failure
    }
}
