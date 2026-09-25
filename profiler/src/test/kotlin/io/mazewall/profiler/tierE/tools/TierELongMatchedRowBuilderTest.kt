package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierELongMatchedRowBuilderTest {
    private val meta = Json
        .parseToJsonElement(
        """{"fork":1,"arm":"dictionary-full-stream-v1","status":0,"timedOut":false,"seed":23063,"declaredOperations":75000,"sourceDigest":"source","imageId":"image","nativeSoSha256":"native","daemonJarsSha256":"jars"}""",
    ).let { it as kotlinx.serialization.json.JsonObject }

    @Test
    fun `arm builder retains exact observed CPU, coverage and identity fields`() {
        val dir = Files.createTempDirectory("tier-e-row-")
        try {
            Files.writeString(dir.resolve("coverage-oracle.json"), """{"seed":23063,"declared_operations":75000}""")
            Files.writeString(dir.resolve("target-cpu.json"), """{"target_cpu_ns":1200,"sample_boundary":"after_workload_before_exit"}""")
            Files.writeString(dir.resolve("collector-process-metrics.json"), """{"cpu_ns":300,"sample_boundary":"after_terminal_report_before_shutdown"}""")
            Files.writeString(dir.resolve("coverage-verdict.json"), """{"coverage_verdict":"PASS","recovered_edges":72,"policy_eligibility":false}""")
            Files.writeString(dir.resolve("terminal-report.json"), """{"evidence_integrity":"COMPLETE","policy_eligibility":false}""")
            val row = TierELongMatchedRowBuilder.build(meta, dir)
            assertEquals("full-stream", row.getValue("emission").jsonPrimitive.content)
            assertEquals("v1", row.getValue("variant").jsonPrimitive.content)
            assertEquals("source", row.getValue("source_digest").jsonPrimitive.content)
            assertEquals(
                1200,
                row
                .getValue("target_cpu_ns")
                .jsonPrimitive.content
                .toInt(),
            )
            assertEquals(
                300,
                row
                .getValue("collector_cpu_ns")
                .jsonPrimitive.content
                .toInt(),
            )
            assertEquals(
                72,
                row
                .getValue("covered_paths")
                .jsonPrimitive.content
                .toInt(),
            )
            assertTrue(row.getValue("artifact_errors").jsonArray.isEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `malformed target sample is an observed error and never invented CPU`() {
        val dir = Files.createTempDirectory("tier-e-row-malformed-")
        try {
            Files.writeString(dir.resolve("target-cpu.json"), "{broken")
            val row = TierELongMatchedRowBuilder.build(meta, dir)
            assertEquals("null", row.getValue("target_cpu_ns").toString())
            assertTrue(row.getValue("artifact_errors").jsonArray.any { it.jsonPrimitive.content.contains("target-cpu.json") })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `timed-out row preserves null observations and nonzero status`() {
        val dir = Files.createTempDirectory("tier-e-row-timeout-")
        try {
            val meta = Json.parseToJsonElement(
                this.meta
                    .toString()
                    .replace("\"status\":0", "\"status\":124")
                .replace("\"timedOut\":false", "\"timedOut\":true"),
            ) as kotlinx.serialization.json.JsonObject
            val row = TierELongMatchedRowBuilder.build(meta, dir)
            assertEquals(
                124,
                row
                .getValue("status")
                .jsonPrimitive.content
                .toInt(),
            )
            assertEquals("true", row.getValue("timed_out").jsonPrimitive.content)
            assertEquals("null", row.getValue("target_cpu_ns").toString())
            assertEquals("null", row.getValue("collector_cpu_ns").toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `baseline row never fabricates collector or terminal evidence`() {
        val dir = Files.createTempDirectory("tier-e-row-baseline-")
        try {
            Files.writeString(dir.resolve("summary.json"), """{"mode":"baseline","policy_eligibility":false}""")
            Files.writeString(dir.resolve("target-cpu.json"), """{"target_cpu_ns":2000,"sample_boundary":"after_workload_before_exit"}""")
            val fields = meta.toMutableMap()
            fields["arm"] = kotlinx.serialization.json.JsonPrimitive("baseline")
            val row = TierELongMatchedRowBuilder.build(kotlinx.serialization.json.JsonObject(fields), dir)
            assertEquals("NOT_APPLICABLE", row.getValue("coverage_verdict").jsonPrimitive.content)
            assertEquals("null", row.getValue("collector_cpu_ns").toString())
            assertEquals("null", row.getValue("terminal_integrity").toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
