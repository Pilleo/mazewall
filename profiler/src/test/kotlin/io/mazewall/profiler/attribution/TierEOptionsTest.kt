package io.mazewall.profiler.internal.attribution
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TierEOptionsTest {
    @Test
    fun `dictionary stack transport is the default`() {
        assertEquals(TierEStackTransport.DICTIONARY, TierEOptions().stackTransport)
    }

    @Test
    fun `coverage recovery defaults to disabled`() {
        assertEquals(CoverageRecoveryMode.DISABLED, TierEOptions().coverageRecoveryMode)
        assertEquals(CoverageRecoveryMode.DEFAULT_MAP_CAPACITY, 4_096)
        assertEquals(4_096, TierEOptions().coverageRecoveryMapCapacity)
    }

    @Test
    fun `coverage recovery requires benchmark phases`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            TierEOptions(coverageRecoveryMode = CoverageRecoveryMode.SHADOW)
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            TierEOptions(coverageRecoveryMode = CoverageRecoveryMode.RECOVER)
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            TierEOptions(coverageRecoveryMapCapacity = 0)
        }
        assertEquals(
            CoverageRecoveryMode.SHADOW,
            TierEOptions(
                benchmarkPhases = true,
                markerVersion = MarkerVersion.V2,
                coverageRecoveryMode = CoverageRecoveryMode.SHADOW,
            ).coverageRecoveryMode,
        )
    }

    @Test
    fun `marker v1 is the default and v2 requires recovery`() {
        assertEquals(MarkerVersion.V1, TierEOptions().markerVersion)
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            TierEOptions(markerVersion = MarkerVersion.V2)
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            TierEOptions(benchmarkPhases = true, coverageRecoveryMode = CoverageRecoveryMode.SHADOW)
        }
        assertEquals(
            MarkerVersion.V2,
            TierEOptions(
                benchmarkPhases = true,
                markerVersion = MarkerVersion.V2,
                coverageRecoveryMode = CoverageRecoveryMode.SHADOW,
            ).markerVersion,
        )
    }

    @Test
    fun `experimental v2 recovery does not force intrusive benchmark phase timing`() {
        val options = TierEOptions(
            markerVersion = MarkerVersion.V2,
            coverageRecoveryMode = CoverageRecoveryMode.RECOVER,
            experimentalCoverage = true,
        )
        assertEquals(false, options.benchmarkPhases)
        assertEquals(CoverageRecoveryMode.RECOVER, options.coverageRecoveryMode)
    }

    @Test
    fun `full stream is the default and is the only full-trace guarantee`() {
        assertEquals(TierEEmissionMode.FULL_STREAM, TierEOptions().emissionMode)

        val full = SessionIntegrity(emissionMode = TierEEmissionMode.FULL_STREAM)
        assertEquals(CaptureGuarantee.FULL_STREAM, full.guarantee)
        assertTrue(full.complete)

        val unique = SessionIntegrity(emissionMode = TierEEmissionMode.UNIQUE_STACK_SYSCALL)
        assertEquals(CaptureGuarantee.UNIQUE_EDGES, unique.guarantee)
        assertFalse(unique.complete)
        assertTrue(unique.coverageComplete)
    }
}
