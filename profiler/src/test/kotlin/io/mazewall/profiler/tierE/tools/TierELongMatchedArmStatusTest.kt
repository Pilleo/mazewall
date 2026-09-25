package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierELongMatchedArmStatusTest {
    @Test
    fun `baseline requires positive target CPU and no collector artifact`() {
        val dir = Files.createTempDirectory("tier-e-baseline-status-")
        try {
            Files.writeString(dir.resolve("summary.json"), """{"mode":"baseline","policy_eligibility":false}""")
            Files.writeString(dir.resolve("target-cpu.json"), """{"target_cpu_ns":1234}""")
            assertTrue(TierELongMatchedArmStatus.complete(dir, true))
            Files.writeString(dir.resolve("collector-process-metrics.json"), """{"cpu_ns":10}""")
            assertFalse(TierELongMatchedArmStatus.complete(dir, true))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `profiled status writes summary but never passes incomplete coverage`() {
        val dir = Files.createTempDirectory("tier-e-profiled-status-")
        try {
            Files.writeString(dir.resolve("coverage-verdict.json"), """{"coverage_verdict":"PASS","recovered_edges":72,"policy_eligibility":false}""")
            Files.writeString(dir.resolve("terminal-report.json"), """{"evidence_integrity":"COMPLETE","policy_eligibility":false}""")
            assertTrue(TierELongMatchedArmStatus.complete(dir, false))
            assertTrue(Files.readString(dir.resolve("summary.json")).contains("\"coverage\""))
            Files.writeString(dir.resolve("coverage-verdict.json"), """{"coverage_verdict":"PASS","recovered_edges":71,"policy_eligibility":false}""")
            assertFalse(TierELongMatchedArmStatus.complete(dir, false))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
