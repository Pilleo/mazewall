package io.mazewall.profiler.tierE.stress

import io.mazewall.profiler.tierE.engine.TierEbpfEngine.BpfProgramExecutionEvidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TierEProgramMissComparisonTest {
    @Test
    fun `independent raw recursion misses remain available without runtime timing statistics`() {
        val before = mapOf(
            "main" to BpfProgramExecutionEvidence.IdentityOnly(321, 1200, "runtime unavailable", 4),
            "audit" to BpfProgramExecutionEvidence.IdentityOnly(322, 900, "runtime unavailable", 5),
        )
        val after = mapOf(
            "main" to BpfProgramExecutionEvidence.IdentityOnly(321, 1200, "runtime unavailable", 4),
            "audit" to BpfProgramExecutionEvidence.IdentityOnly(322, 900, "runtime unavailable", 8),
        )
        val delta = TierEProgramMissComparison.compare(before, after)
        assertEquals(0L, delta.main)
        assertEquals(3L, delta.raw)
    }

    @Test
    fun `unavailable counter remains unavailable and changed program identity fails closed`() {
        val before = mapOf(
            "main" to BpfProgramExecutionEvidence.IdentityOnly(321, 1200, "runtime unavailable", null),
            "audit" to BpfProgramExecutionEvidence.IdentityOnly(322, 900, "runtime unavailable", null),
        )
        val unavailable = TierEProgramMissComparison.compare(before, before)
        assertEquals(null, unavailable.main)
        assertEquals(null, unavailable.raw)
        val changed = before + ("audit" to BpfProgramExecutionEvidence.IdentityOnly(323, 900, "runtime unavailable", 1))
        assertThrows(IllegalStateException::class.java) { TierEProgramMissComparison.compare(before, changed) }
    }
}
