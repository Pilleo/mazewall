package io.mazewall.profiler.tierE.engine

import io.mazewall.profiler.internal.attribution.TierEOptions
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TierEInvocationWitnessBytecodeTest {
    private val enabled = TierEOptions(
        benchmarkPhases = true,
        benchmarkRawSysEnterAudit = true,
        benchmarkExactAdmissionAudit = true,
        benchmarkInvocationIdWitness = true,
    )

    @Test
    fun `raw and main observe the marker token before their independent FD argument read`() {
        val raw = TierEBpfProgramBuilder.buildRawTracepointSysEnterAuditProgram(
            earlyTargetCount = true,
            benchmarkTargetTgid = 12345,
            invocationWitness = true,
        )
        val main = TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram(enabled, benchmarkTargetTgid = 12345)
        for (program in listOf(raw, main)) {
            val markerRead = program.indexOfFirst { it.code == 0x85 && it.imm == 156 }
            val fdRead = program.indexOfFirst { it.code == 0x85 && it.imm == 113 }
            assertTrue(markerRead >= 0 && markerRead < fdRead, "witness must precede even a failed FD read")
            assertTrue(program.take(fdRead).any { it.code == 0x85 && it.imm == 158 }, "task identity must come from the marker-owned task")
        }
        val defaultRaw = TierEBpfProgramBuilder.buildRawTracepointSysEnterAuditProgram(true, 12345)
        assertTrue(defaultRaw.none { it.code == 0x85 && it.imm == 156 }, "ordinary audit bytecode must remain uninstrumented")
    }
}
