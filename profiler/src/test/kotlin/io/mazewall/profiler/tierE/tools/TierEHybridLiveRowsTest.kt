package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierEHybridLiveRowsTest {
    @Test
    fun `live matrix requires published native snapshots even when base fork is complete`() {
        val dir = Files.createTempDirectory("tier-e-live-row-")
        try {
            val batches = (1..10).joinToString("\n") {
                "tier-e-shared-openat-oracle-batch: phase=measured index=$it operations=200 workloadNs=1000000"
            }
            Files.writeString(
                dir.resolve("target.log"),
                "$batches\nsplit-benchmark-target: mode=dictionary-full-stream elapsedNs=100 cpuNs=90 peakRssKb=80\n" +
                "tier-e-benchmark: publication_cpu_ns=40 publication_bytes=100 peak_retained_bytes=120\n",
            )
            Files.writeString(dir.resolve("collector-process-metrics.json"), """{"cpu_ns":20,"peak_rss_kb":30}""")
            Files.writeString(dir.resolve("operation-verdict.json"), """{"operation_coverage_verdict":"PASS"}""")
            Files.writeString(dir.resolve("terminal-report.json"), """{"evidence_integrity":"COMPLETE","recovery_evidence":{"recovery_verdict":"COMPLETE","native_live_publications":0}}""")
            val row = TierEHybridLiveRows.row(dir, 0, "dictionary-full-stream", "syscall_dense", true, 0, 2000)
            assertEquals("false", row.getValue("fork_complete").toString())
            assertEquals("live_dependency_admission_incomplete", row.getValue("incomplete_reason").toString().trim('"'))
            Files.writeString(dir.resolve("terminal-report.json"), Files.readString(dir.resolve("terminal-report.json")).replace("\"native_live_publications\":0", "\"native_live_publications\":1"))
            val good = TierEHybridLiveRows.row(dir, 0, "dictionary-full-stream", "syscall_dense", true, 0, 2000)
            assertEquals("true", good.getValue("fork_complete").toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
