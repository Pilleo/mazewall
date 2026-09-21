package io.mazewall.profiler.tierE.stress

import io.mazewall.profiler.tierE.engine.TierEbpfEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TierEKernelSmokeTest {
    @Test
    fun `audit sample retains the complete population at capacity`() {
        val population = (0 until TierEbpfEngine.BENCHMARK_AUDIT_MAX_DESCRIPTORS).toSet()

        assertEquals(population, TierEKernelSmoke.boundedAuditSample(population))
    }

    @Test
    fun `audit sample is bounded deterministic and distributed across a large population`() {
        val population = (0 until 300_000).toSet()

        val first = TierEKernelSmoke.boundedAuditSample(population)
        val second = TierEKernelSmoke.boundedAuditSample(population)

        assertEquals(TierEbpfEngine.BENCHMARK_AUDIT_MAX_DESCRIPTORS, first.size)
        assertEquals(first, second)
        assertEquals(0, first.min())
        assertTrue(first.max() > 299_000)
    }

    @Test
    fun `audit sample rejects a non-positive capacity`() {
        assertFailsWith<IllegalArgumentException> {
            TierEKernelSmoke.boundedAuditSample(setOf(1), limit = 0)
        }
    }
}
