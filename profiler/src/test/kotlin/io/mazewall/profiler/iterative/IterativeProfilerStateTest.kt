package io.mazewall.profiler.iterative

import io.mazewall.Policy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class IterativeProfilerStateTest {

    @Test
    fun `test states`() {
        val policy = Policy.PURE_COMPUTE_UNSAFE
        val error = RuntimeException("test error")

        val running = IterativeProfilerState.Running(policy, 1)
        assertEquals(policy, running.policy)
        assertEquals(1, running.iteration)

        val analyzing = IterativeProfilerState.Analyzing(policy, error, 2)
        assertEquals(policy, analyzing.policy)
        assertEquals(error, analyzing.error)
        assertEquals(2, analyzing.iteration)

        val updating = IterativeProfilerState.Updating(policy, "/path", 3)
        assertEquals(policy, updating.policy)
        assertEquals("/path", updating.path)
        assertEquals(3, updating.iteration)

        val converged = IterativeProfilerState.Converged(policy)
        assertEquals(policy, converged.policy)

        val exceeded = IterativeProfilerState.Exceeded(policy)
        assertEquals(policy, exceeded.policy)

        val failed = IterativeProfilerState.Failed(policy, error)
        assertEquals(policy, failed.policy)
        assertEquals(error, failed.error)
    }
}
