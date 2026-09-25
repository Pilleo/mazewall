package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierEHybridFaultRowsTest {
    @Test
    fun `fault matrix row and decision require map singleton discovery and bound p99`() {
        val directory = Files.createTempDirectory("tier-e-fault-row-")
        try {
            Files.writeString(
                directory.resolve("terminal-report.json"),
                """{"evidence_integrity":"INCOMPLETE","transport_completeness":false,"recovery_evidence":{"recovery_verdict":"COMPLETE","confirmed_ring_loss_recoveries":24,"discovery_samples":24,"discovery_p99_upper_ns":400000000,"dictionary_waiting_max_ns":300,"native_live_publications":0}}""",
            )
            Files.writeString(directory.resolve("coverage-verdict.json"), """{"coverage_verdict":"PASS","recovered_edges":72}""")
            val edges = (48..71).joinToString(",") { """{"workload_path":$it,"discovery_source":"map"}""" }
            Files.writeString(directory.resolve("coverage-manifest.json"), """{"edges":[$edges]}""")
            val row = TierEHybridFaultRows.row(directory, 0, "full-stream", 0)
            assertEquals("true", row.getValue("complete").toString())
            Files.writeString(directory.resolve("fork-results.jsonl"), "$row\n")
            assertEquals("true", TierEHybridFaultRows.summary(directory, 0, 0).getValue("discovery_p99_under_500ms").toString())
            Files.writeString(directory.resolve("terminal-report.json"), Files.readString(directory.resolve("terminal-report.json")).replace("400000000", "600000000"))
            val slow = TierEHybridFaultRows.row(directory, 0, "full-stream", 0)
            Files.writeString(directory.resolve("fork-results.jsonl"), "$slow\n")
            assertEquals("false", TierEHybridFaultRows.summary(directory, 0, 0).getValue("discovery_p99_under_500ms").toString())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
