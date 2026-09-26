package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierEFixtureVerdictTest {
    @Test
    fun `allowed incomplete accepts only matching incomplete coverage and terminal`() {
        val directory = Files.createTempDirectory("tier-e-allow-incomplete-")
        try {
            Files.writeString(
                directory.resolve("terminal-report.json"),
                """{"evidence_integrity":"INCOMPLETE","policy_eligibility":false}""",
            )
            Files.writeString(
                directory.resolve("coverage-verdict.json"),
                """{"coverage_verdict":"INCOMPLETE","wrong_edges":[],"policy_eligibility":false}""",
            )
            assertTrue(TierEFixtureVerdict.validate(directory, "allow-incomplete").contains("coverage_verdict=INCOMPLETE"))
            Files.writeString(
                directory.resolve("coverage-verdict.json"),
                """{"coverage_verdict":"PASS","wrong_edges":[],"policy_eligibility":false}""",
            )
            assertThrows(IllegalArgumentException::class.java) { TierEFixtureVerdict.validate(directory, "allow-incomplete") }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `fault mode requires map sourced singleton recovery and incomplete transport`() {
        val directory = Files.createTempDirectory("tier-e-kotlin-fault-verdict-")
        try {
            Files.writeString(
                directory.resolve("terminal-report.json"),
                """{"evidence_integrity":"INCOMPLETE","transport_completeness":false,"attribution_coverage":false,"lifecycle_validity":true,"recovery_evidence":{"insertion_failures":0,"recovered_edges":24,"confirmed_ring_loss_recoveries":24},"policy_eligibility":false}""",
            )
            Files.writeString(directory.resolve("coverage-verdict.json"), """{"coverage_verdict":"PASS","wrong_edges":[],"policy_eligibility":false}""")
            val edges = (48..71).joinToString(",") { """{"workload_path":$it,"discovery_source":"map"}""" }
            Files.writeString(directory.resolve("coverage-manifest.json"), """{"edges":[$edges]}""")
            val verdict = TierEFixtureVerdict.validate(directory, "fault")
            assertTrue(verdict.contains("ring_transport=INCOMPLETE"))
            Files.writeString(directory.resolve("coverage-manifest.json"), """{"edges":[${edges.replaceFirst("\"map\"", "\"ring\"") }]}""")
            assertThrows(IllegalArgumentException::class.java) { TierEFixtureVerdict.validate(directory, "fault") }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `normal acceptance requires complete terminal dimensions`() {
        val directory = Files.createTempDirectory("tier-e-kotlin-terminal-verdict-")
        try {
            Files.writeString(
                directory.resolve("terminal-report.json"),
                """{"evidence_integrity":"COMPLETE","policy_eligibility":false,"capture_domain_validity":true,"transport_completeness":true,"attribution_coverage":true,"dictionary_integrity":true,"lifecycle_validity":false}""",
            )
            assertThrows(IllegalArgumentException::class.java) { TierEFixtureVerdict.validate(directory, "normal") }
            Files.writeString(
                directory.resolve("terminal-report.json"),
                Files.readString(directory.resolve("terminal-report.json")).replace("\"lifecycle_validity\":false", "\"lifecycle_validity\":true"),
            )
            assertTrue(TierEFixtureVerdict.validate(directory, "normal").contains("lifecycle_validity=PASS"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
