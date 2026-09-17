package io.mazewall.profiler.tierE.stress

import kotlin.test.Test
import kotlin.test.assertEquals

class TierEMatchedBenchmarkJsonTest {
    @Test
    fun `capture profile scope pairs full and narrowed dictionary captures`() {
        assertEquals(
            setOf(
                "baseline",
                "dictionary_full_stream_profile_full",
                "dictionary_full_stream_profile_jdk25_unix_openat0",
            ),
            TierEMatchedBenchmark.captureProfileVariantNames(0).toSet(),
        )
    }

    @Test
    fun `full stream exact joins produce valid capture efficiency evidence`() {
        val metrics = TierEMatchedBenchmark.captureEfficiency(
            executedOperations = 1_300,
            exactCaptures = 7_815,
            resolvedSyscalls = 1_300,
            productiveCaptures = 1_300,
            getStackTraceNanos = 25_000_000,
            fullStream = true,
            exactJoinComplete = true,
            evidenceComplete = true,
            observedLosses = 0,
            attributionFailures = 0,
        )

        assertEquals(1_300, metrics.executedOperations)
        assertEquals(7_815, metrics.exactCaptures)
        assertEquals(1_300, metrics.productiveCaptures)
        assertEquals(true, metrics.valid)
    }

    @Test
    fun `capture yield bindings retain exact usefulness counters`() {
        val bindings = TierEMatchedBenchmark.captureYieldBindings(
            "capture_yield_bindings=2:4:4:0:0:2:3:2",
        )

        val binding = bindings.getValue(2)
        assertEquals(4, binding.records)
        assertEquals(2, binding.resolvedInvocations)
        assertEquals(3, binding.resolvedSyscalls)
        assertEquals(2, binding.capturedWithoutResolvedSyscall)
    }

    @Test
    fun `verified baseline receives an admissible performance label`() {
        assertEquals(
            "BASELINE_VERIFIED",
            TierEMatchedBenchmark.performanceEvidence(
                TierEBenchmarkContract.CorrectnessState.PASS,
                TierEBenchmarkContract.EvidenceState.NOT_APPLICABLE,
            ),
        )
    }

    @Test
    fun `artifact escaping keeps child control output valid JSON`() {
        assertEquals(
            "failure\\u0000\\t\\n\\r\\b\\f\\\"\\\\",
            TierEMatchedBenchmark.escape("failure\u0000\t\n\r\b\u000c\"\\"),
        )
    }
}
