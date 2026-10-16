package io.mazewall.profiler.tierE.stress

import io.mazewall.profiler.internal.attribution.TierEKernelCoverageMode
import io.mazewall.profiler.internal.attribution.TierEOptions
import io.mazewall.profiler.internal.attribution.TierESysEnterAttachmentBackend
import io.mazewall.profiler.internal.attribution.TierESysEnterAttachmentShape
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
    fun `audit sample retains the sustained 1250 operation population`() {
        val population = (0 until 75_600).toSet()

        assertEquals(population, TierEKernelSmoke.boundedAuditSample(population))
    }

    @Test
    fun `audit sample rejects a non-positive capacity`() {
        assertFailsWith<IllegalArgumentException> {
            TierEKernelSmoke.boundedAuditSample(setOf(1), limit = 0)
        }
    }

    @Test
    fun `sys enter backend parser preserves auto and accepts explicit diagnostic backends`() {
        assertEquals(TierESysEnterAttachmentBackend.AUTO, TierEKernelSmoke.sysEnterAttachmentBackend("auto"))
        assertEquals(
            TierESysEnterAttachmentBackend.DIRECT_RAW_TRACEPOINT,
            TierEKernelSmoke.sysEnterAttachmentBackend("direct-raw-tracepoint"),
        )
        assertEquals(
            TierESysEnterAttachmentBackend.PERF_TRACEPOINT_COMPAT,
            TierEKernelSmoke.sysEnterAttachmentBackend("perf-tracepoint-compat"),
        )
        assertFailsWith<IllegalStateException> {
            TierEKernelSmoke.sysEnterAttachmentBackend("unsupported")
        }
    }

    @Test
    fun `kernel coverage parser accepts every benchmark diagnostic variant`() {
        assertEquals(TierEKernelCoverageMode.NONE, TierEKernelSmoke.kernelCoverageMode("none"))
        assertEquals(TierEKernelCoverageMode.DIAGNOSTIC_LOOKUP, TierEKernelSmoke.kernelCoverageMode("diagnostic-lookup"))
        assertEquals(TierEKernelCoverageMode.DIAGNOSTIC_UPDATE, TierEKernelSmoke.kernelCoverageMode("diagnostic-update"))
        assertFailsWith<IllegalStateException> { TierEKernelSmoke.kernelCoverageMode("unknown") }
    }

    @Test
    fun `sys enter attachment shape parser accepts benchmark controls`() {
        assertEquals(TierESysEnterAttachmentShape.MAIN_PLUS_AUDIT, TierEKernelSmoke.sysEnterAttachmentShape("main-plus-audit"))
        assertEquals(TierESysEnterAttachmentShape.DESCRIPTOR_AUDIT_ONLY, TierEKernelSmoke.sysEnterAttachmentShape("descriptor-audit-only"))
        assertEquals(TierESysEnterAttachmentShape.MINIMAL_AUDIT_ONLY, TierEKernelSmoke.sysEnterAttachmentShape("minimal-audit-only"))
        assertEquals(TierESysEnterAttachmentShape.MINIMAL_COUNTER_ONLY, TierEKernelSmoke.sysEnterAttachmentShape("minimal-counter-only"))
        assertFailsWith<IllegalStateException> { TierEKernelSmoke.sysEnterAttachmentShape("unknown") }
    }

    @Test
    fun `attachment-only diagnostic shape remains benchmark-only`() {
        assertFailsWith<IllegalArgumentException> {
            TierEOptions(benchmarkSysEnterAttachmentShape = TierESysEnterAttachmentShape.DESCRIPTOR_AUDIT_ONLY)
        }
        TierEOptions(
            benchmarkPhases = true,
            benchmarkRawSysEnterAudit = true,
            benchmarkSysEnterAttachmentShape = TierESysEnterAttachmentShape.DESCRIPTOR_AUDIT_ONLY,
        )
    }
}
