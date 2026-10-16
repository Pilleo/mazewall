package io.mazewall.profiler.internal.attribution
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TierEPerformanceGateTest {
    @Test
    fun `candidate within the review threshold passes`() {
        val baseline = measurement(100)
        val candidate = measurement(124)

        assertTrue(TierEPerformanceGate.evaluate(baseline, candidate).passed)
    }

    @Test
    fun `candidate over the review threshold fails by metric`() {
        val baseline = measurement(100)
        val candidate = measurement(126)

        val result = TierEPerformanceGate.evaluate(baseline, candidate)

        assertFalse(result.passed)
        assertTrue("workload_total_nanos" in result.exceededMetrics)
    }

    @Test
    fun `changed logical stacks fail independently of timing`() {
        val baseline = measurement(100, digest = "before")
        val candidate = measurement(100, digest = "after")

        val result = TierEPerformanceGate.evaluate(baseline, candidate)

        assertFalse(result.passed)
        assertTrue(result.reason!!.contains("logical stack"))
    }

    private fun measurement(
        value: Long,
        digest: String = "same",
    ) = TierEPerformanceMeasurement(
        mode = "full-stream",
        warmedRuns = 10,
        getStackTraceNanos = value,
        rawKeyValidationNanos = value,
        metadataResolutionNanos = value,
        allocationBytes = value,
        peakRetainedBytes = value,
        tailLatencyNanos = value,
        logicalStackDigest = digest,
    )
}
