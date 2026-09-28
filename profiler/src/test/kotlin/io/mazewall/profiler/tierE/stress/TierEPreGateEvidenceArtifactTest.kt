package io.mazewall.profiler.tierE.stress

import io.mazewall.profiler.tierE.engine.TierEPreGateAuditCount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class TierEPreGateEvidenceArtifactTest {
    @TempDir
    lateinit var output: Path

    @Test
    fun `complete declared ID artifact retains high missing FD after seventeen low repeated IDs`() {
        val complete = TierEPreGateAuditCount(1, 1, 1, 1, 1)
        val counts = (1..17).associateWith { complete.copy(rawBeforeSession = 2, rawAfterSession = 2) } +
            (7746 to complete.copy(rawBeforeSession = 0, rawAfterSession = 0))
        val path = output.resolve("pre-gate.tsv")
        val receipt = TierEPreGateEvidenceArtifact.write(
            path,
            targetPid = 4321,
            expected = counts.keys,
            counts = counts,
            rawTarget = counts.mapValues { (fd, _) -> if (fd == 7746) 0L else 1L },
            mainSubmitted = counts.mapValues { 1L },
        )
        val lines = Files.readAllLines(path)
        assertEquals(18, receipt.rows)
        assertEquals(20, lines.size)
        assertTrue(lines.any { it.startsWith("7746\t1\t0\t1\t0\t1\t0\t1") })
        assertEquals(receipt.sha256, TierEPreGateEvidenceArtifact.sha256(path))
    }

    @Test
    fun `missing declared ID aborts publication rather than writing a partial artifact`() {
        val path = output.resolve("pre-gate.tsv")
        assertThrows(IllegalArgumentException::class.java) {
            TierEPreGateEvidenceArtifact.write(
                path,
                targetPid = 4321,
                expected = setOf(1, 7746),
                counts = mapOf(1 to TierEPreGateAuditCount(1, 1, 1, 1, 1)),
                rawTarget = mapOf(1 to 1L, 7746 to 0L),
                mainSubmitted = mapOf(1 to 1L, 7746 to 1L),
            )
        }
        assertFalse(Files.exists(path))
    }

    @Test
    fun `fifth contract binds every row to the independent oracle and exact loaded programs`() {
        val path = output.resolve("pre-gate-v5.tsv")
        TierEPreGateEvidenceArtifact.write(
            path,
            targetPid = 4321,
            expected = setOf(7746),
            counts = mapOf(7746 to TierEPreGateAuditCount(1, 1, 1, 1, 1)),
            rawTarget = mapOf(7746 to 1L),
            mainSubmitted = mapOf(7746 to 1L),
            oracleSha256 = "a".repeat(64),
            mainProgramSha256 = "b".repeat(64),
            rawProgramSha256 = "c".repeat(64),
        )
        val header = Files.readAllLines(path).first()
        assertTrue("schema_version=5" in header)
        assertTrue("declared_sha256=${"a".repeat(64)}" in header)
        assertTrue("main_program_sha256=${"b".repeat(64)}" in header)
    }
}
