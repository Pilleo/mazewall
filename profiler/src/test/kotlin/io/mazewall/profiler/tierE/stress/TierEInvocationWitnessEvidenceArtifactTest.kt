package io.mazewall.profiler.tierE.stress

import io.mazewall.profiler.tierE.engine.TierEBenchmarkAuditMapReader.InvocationWitnessCount
import io.mazewall.profiler.tierE.engine.TierEPreGateAuditCount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class TierEInvocationWitnessEvidenceArtifactTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `complete artifact binds native token and both loaded programs to declared FD`() {
        val path = directory.resolve("invocation-fd-fork-1.tsv")
        val receipt = TierEInvocationWitnessEvidenceArtifact.write(
            path,
            targetPid = 222,
            nativeIds = mapOf(13 to 0x4d5a0000002aL),
            tokenCounts = mapOf(0x4d5a0000002aL to InvocationWitnessCount(1, 0)),
            fdCounts = mapOf(13 to TierEPreGateAuditCount(1, 1, 0, 1, 0)),
            oracleSha256 = "a".repeat(64),
            nativeSha256 = "b".repeat(64),
            rawProgramSha256 = "c".repeat(64),
            mainProgramSha256 = "d".repeat(64),
            failures = (9..14).associateWith { 0L },
        )
        assertEquals(1, receipt.rows)
        assertEquals(
            "13\t85048942395434\t1\t0\t1\t0\tMAIN_BEFORE_TOKEN",
            Files.readAllLines(path)[2],
        )
        assertEquals(64, receipt.sha256.length)
    }

    @Test
    fun `artifact refuses invented or omitted witness failure counters`() {
        assertThrows(IllegalStateException::class.java) {
            TierEInvocationWitnessEvidenceArtifact.write(
                directory.resolve("missing.tsv"),
                222,
                mapOf(13 to 42L),
                mapOf(42L to InvocationWitnessCount(1, 1)),
                mapOf(13 to TierEPreGateAuditCount(1, 1, 1, 1, 1)),
                "a".repeat(64),
                "b".repeat(64),
                "c".repeat(64),
                "d".repeat(64),
                emptyMap(),
            )
        }
    }
}
