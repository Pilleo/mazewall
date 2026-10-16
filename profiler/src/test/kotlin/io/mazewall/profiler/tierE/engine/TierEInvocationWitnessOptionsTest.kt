package io.mazewall.profiler.tierE.engine

import io.mazewall.profiler.internal.attribution.TierEOptions
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TierEInvocationWitnessOptionsTest {
    @Test
    fun `invocation witness is opt in and cannot run outside paired benchmark audit`() {
        assertFalse(TierEOptions().benchmarkInvocationIdWitness)
        assertThrows(IllegalArgumentException::class.java) { TierEOptions(benchmarkInvocationIdWitness = true) }
        assertThrows(IllegalArgumentException::class.java) {
            TierEOptions(benchmarkPhases = true, benchmarkRawSysEnterAudit = true, benchmarkInvocationIdWitness = true)
        }
        assertTrue(
            TierEOptions(
                benchmarkPhases = true,
                benchmarkRawSysEnterAudit = true,
            benchmarkExactAdmissionAudit = true,
                benchmarkInvocationIdWitness = true,
            ).benchmarkInvocationIdWitness,
        )
    }
}
