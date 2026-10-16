package io.mazewall.profiler.iterative
import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.api.Policy
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@EnabledIfLinuxAndSupported
class IterativeClockPreloadTest {
    @Test
    fun `preloads clock wrappers before starting a restrictive worker`() {
        val events = mutableListOf<String>()

        val failure = RealIterativeTaskExecutor(1_000) { events += "preload" }
            .executeTask(Policy.PURE_COMPUTE_UNSAFE) { events += "task" }

        assertNull(failure)
        assertEquals(listOf("preload", "task"), events)
    }

    @Test
    fun `clock wrappers remain usable in a restrictive fresh iteration`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "clockIteration")
    }

    fun clockIteration() {
        val failure = RealIterativeTaskExecutor().executeTask(Policy.PURE_COMPUTE_UNSAFE) {
            check(System.currentTimeMillis() > 0) { "contained wall clock returned no timestamp" }
            check(System.nanoTime() != 0L) { "contained monotonic clock returned no timestamp" }
        }
        if (failure != null) throw failure
    }
}
