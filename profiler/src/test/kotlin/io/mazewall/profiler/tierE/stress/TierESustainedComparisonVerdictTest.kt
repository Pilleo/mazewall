package io.mazewall.profiler.tierE.stress

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TierESustainedComparisonVerdictTest {
    @Test
    fun `audit disabled operation gap remains incomplete without inventing diagnostic evidence`() {
        val result = TierESustainedComparisonVerdict.evaluate(
            expected = 10000,
            retainedIds = 10000,
            oracleAvailable = true,
            operationOracleComplete = false,
            auditsEnabled = false,
            pairedDiagnosticComplete = true,
        )
        assertEquals("INCOMPLETE", result.declaredOperations.name)
        assertEquals("DISABLED", result.diagnosticIntegrity.name)
        assertEquals("UNVERIFIED", result.qualification)
    }

    @Test
    fun `complete declared IDs do not turn an audited diagnostic failure into qualification`() {
        val result = TierESustainedComparisonVerdict.evaluate(
            expected = 10000,
            retainedIds = 10000,
            oracleAvailable = true,
            operationOracleComplete = true,
            auditsEnabled = true,
            pairedDiagnosticComplete = false,
        )
        assertEquals("PASS", result.declaredOperations.name)
        assertEquals("INCOMPLETE", result.diagnosticIntegrity.name)
        assertEquals("UNVERIFIED", result.qualification)
    }

    @Test
    fun `missing target manifest cannot pass despite apparently complete collector oracle`() {
        val result = TierESustainedComparisonVerdict.evaluate(
            expected = 10000,
            retainedIds = null,
            oracleAvailable = true,
            operationOracleComplete = true,
            auditsEnabled = false,
            pairedDiagnosticComplete = true,
        )
        assertEquals("INCOMPLETE", result.declaredOperations.name)
    }
}
