package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierESplitMatchedSummaryTest {
    @Test
    fun `matched summary excludes instrumented workloads from timing median and rejects drift`() {
        val dir = Files.createTempDirectory("tier-e-short-summary-")
        try {
            Files.writeString(
                dir.resolve("fork-results.jsonl"),
                """{"fork":0,"mode":"baseline","fork_complete":true,"instrumented":false,"workload_ns":[110000000],"target_cpu_ns":5,"target_peak_rss_kb":6}
{"fork":0,"mode":"dictionary-full-stream","fork_complete":true,"instrumented":true,"workload_ns":[900000000],"target_cpu_ns":7,"target_peak_rss_kb":8,"collector_cpu_ns":9,"collector_peak_rss_kb":10,"native_phases":{"publication_bytes":12,"publication_cpu_ns":15},"collector_receipt":{"receipt_samples":2,"receipt_p99_upper_ns":3}}
{"fork":1,"mode":"dictionary-full-stream","fork_complete":true,"instrumented":false,"workload_ns":[120000000],"target_cpu_ns":11,"target_peak_rss_kb":12,"collector_cpu_ns":13,"collector_peak_rss_kb":14}
""",
            )
            val result = TierESplitMatchedSummary.summarize(dir, 0, 0)
            assertEquals("true", result.getValue("terminal").let { (it as kotlinx.serialization.json.JsonObject).getValue("success").toString() })
            assertEquals("met", result.getValue("calibration_target").toString().trim('"'))
            val mode = (result.getValue("modes") as kotlinx.serialization.json.JsonObject).getValue("dictionary-full-stream") as kotlinx.serialization.json.JsonObject
            assertEquals("120000000", mode.getValue("measured_batch_median_ns").toString())
            assertEquals("12", mode.getValue("publication_bytes_median").toString())
            assertEquals("false", (TierESplitMatchedSummary.summarize(dir, 0, 1).getValue("terminal") as kotlinx.serialization.json.JsonObject).getValue("success").toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
