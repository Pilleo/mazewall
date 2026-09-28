package io.mazewall.profiler.tierE.engine

import io.mazewall.profiler.internal.attribution.TierEOptions
import io.mazewall.profiler.internal.attribution.TierESysEnterAttachmentBackend
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.foreign.Arena

class TierEInstalledProgramIdentityTest {
    @Test
    fun `identity hashes the exact emitted raw program including installed target PID`() {
        val options = TierEOptions(benchmarkPhases = true, benchmarkRawSysEnterAudit = true, benchmarkExactAdmissionAudit = true)
        val first = TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram(options, benchmarkTargetTgid = 12345)
        val second = TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram(options, benchmarkTargetTgid = 12346)
        Arena.ofConfined().use { arena ->
            fun encoded(
                program: List<TierEbpfEngine.Insn>,
                mapBase: Int,
            ) = BpfProgLoadRequestEncoder
                .encode(
                arena,
                program,
                List(11) { it + mapBase },
                17,
                "tier_e_syscall",
            ).instructions
            val bytes = encoded(first, 100)
            val evidence = TierEInstalledProgramIdentity.from("main", TierESysEnterAttachmentBackend.DIRECT_RAW_TRACEPOINT, bytes)
            assertEquals(evidence, TierEInstalledProgramIdentity.from("main", TierESysEnterAttachmentBackend.DIRECT_RAW_TRACEPOINT, bytes))
            assertEquals(first.size, evidence.instructionCount)
            assertTrue(evidence.sha256.matches(Regex("[a-f0-9]{64}")))
            assertNotEquals(evidence.sha256, TierEInstalledProgramIdentity.from("main", TierESysEnterAttachmentBackend.DIRECT_RAW_TRACEPOINT, encoded(second, 100)).sha256)
            assertNotEquals(evidence.sha256, TierEInstalledProgramIdentity.from("main", TierESysEnterAttachmentBackend.DIRECT_RAW_TRACEPOINT, encoded(first, 200)).sha256)
        }
    }

    @Test
    fun `program identity survives absent kernel runtime statistics`() {
        val evidence = TierEInstalledProgramIdentity.kernelInfo(
            returnedLength = 232,
            programId = 321,
            translatedBytes = 1234,
            runCount = 0,
            runTimeNanos = 0,
            recursionMisses = 3,
        )
        assertEquals(321, evidence.programId)
        assertEquals(1234, evidence.translatedInstructionBytes)
        assertTrue(evidence.runtimeUnavailable)
        assertEquals(3L, evidence.recursionMisses)
    }

    @Test
    fun `short kernel program info reports missing recursion field as unavailable not zero`() {
        val evidence = TierEInstalledProgramIdentity.kernelInfo(
            returnedLength = 208,
            programId = 321,
            translatedBytes = 1234,
            runCount = null,
            runTimeNanos = null,
            recursionMisses = null,
        )
        assertEquals(null, evidence.recursionMisses)
        assertTrue(evidence.runtimeUnavailable)
    }
}
