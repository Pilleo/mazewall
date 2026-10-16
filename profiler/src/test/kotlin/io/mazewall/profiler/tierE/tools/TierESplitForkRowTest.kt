package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierESplitForkRowTest {
    @Test
    fun `short matched row requires ten complete batches and profiler terminal`() {
        val directory = Files.createTempDirectory("tier-e-short-row-")
        try {
            val batches = (1..10).joinToString("\n") {
                "tier-e-shared-openat-oracle-batch: phase=measured index=$it operations=200 checksum=1 verified=true workloadNs=${1000000 + it}"
            }
            Files.writeString(directory.resolve("target.log"), "$batches\nsplit-benchmark-target: mode=dictionary-full-stream elapsedNs=7000 cpuNs=6000 peakRssKb=79804\n")
            Files.writeString(directory.resolve("terminal-report.json"), """{"evidence_integrity":"COMPLETE"}""")
            Files.writeString(directory.resolve("collector-process-metrics.json"), """{"cpu_ns":2000,"peak_rss_kb":300000}""")
            Files.writeString(directory.resolve("operation-verdict.json"), """{"operation_coverage_verdict":"PASS","event_history_verdict":"PASS"}""")
            Files.writeString(directory.resolve("benchmark-oracle.json"), """{"expected_operations":2000}""")
            val valid = TierESplitForkRow.parse(0, "dictionary-full-stream", 0, directory, 10, 2000)
            assertEquals("true", valid.getValue("fork_complete").toString())
            Files.writeString(directory.resolve("terminal-report.json"), """{"evidence_integrity":"INCOMPLETE"}""")
            val invalid = TierESplitForkRow.parse(0, "dictionary-full-stream", 0, directory, 10, 2000)
            assertEquals("terminal_incomplete", invalid.getValue("incomplete_reason").toString().trim('"'))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
