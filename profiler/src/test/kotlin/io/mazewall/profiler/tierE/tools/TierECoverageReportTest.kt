package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierECoverageReportTest {
    @Test
    fun `full-stack comparison needs all 72 shared paths and writes reproducible checksums`() {
        val directory = Files.createTempDirectory("tier-e-coverage-report-")
        try {
            val manifests = Files.createDirectory(directory.resolve("coverage-manifests"))
            val stacks = (0..71).joinToString(",", "{", "}") { path ->
                "\"$path\":[[\"257\",\"0\",\"COMPLETE\",\"loader-3\",\"Owner\",\"invoke\",\"()V\",\"10\",\"JAVA\"]]"
            }
            Files.writeString(manifests.resolve("dictionary_full_stream-fork-1.txt.stacks.json"), stacks)
            Files.writeString(
                directory.resolve("results.jsonl"),
                """{"kind":"metadata","forks":1}
{"kind":"coverage","variant":"dictionary_full_stream","fork":1,"report":{"coverage_verdict":"PASS","recovered":[0]}}
{"kind":"terminal","success":true}
""",
            )
            TierECoverageReport.write(directory)
            val summary = Json.parseToJsonElement(Files.readString(directory.resolve("coverage-summary.json"))).jsonObject
            assertEquals(
                "\"PASS\"",
                summary
                .getValue("full_stack_comparison")
                .jsonArray[0]
                .jsonObject
                .getValue("status")
                .toString(),
            )
            val checksums = Files.readAllLines(directory.resolve("coverage-checksums.sha256"))
            assertEquals(2, checksums.size)
            assertTrue(checksums[0].endsWith("  results.jsonl"))
            assertTrue(checksums[1].endsWith("  coverage-summary.json"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
