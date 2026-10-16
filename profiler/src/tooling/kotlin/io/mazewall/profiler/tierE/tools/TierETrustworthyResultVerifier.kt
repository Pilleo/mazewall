package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Require an intact benchmark JSONL before retiring the source artifact volume. */
public object TierETrustworthyResultVerifier {
    public fun verify(file: Path): Boolean {
        return verify(file, preGateRequired = false)
    }

    /** Audit-enabled exports must retain their complete version-four per-ID snapshot. */
    public fun verify(
        file: Path,
        preGateRequired: Boolean,
        comparisonRequired: Boolean = false,
    ): Boolean {
        val text = Files.readString(file)
        require(text.endsWith('\n')) { "Torn benchmark JSONL tail" }
        val rows = text
            .lineSequence()
            .filter(String::isNotBlank)
            .map { line ->
            Json.parseToJsonElement(line) as? JsonObject ?: throw IllegalArgumentException("Invalid benchmark result row")
        }.toList()

        fun kind(row: JsonObject) = row["kind"]?.jsonPrimitive?.content
        val terminal = rows.filter { kind(it) == "terminal" }
        val summaries = rows.filter { kind(it) == "summary" }
        require(
            terminal.size == 1 &&
            terminal.single()["success"] == JsonPrimitive(true) &&
            summaries.size == 1 &&
            rows.any { kind(it) == "fork-output" },
        ) { "Incomplete benchmark terminal rows" }
        val samples = (summaries.single()["sample_count"] as? JsonPrimitive)?.intOrNull
        require(samples != null && samples >= 0 && rows.count { kind(it) == "sample" } == samples) {
            "Benchmark sample count mismatch"
        }
        val expectedOperations = rows
            .singleOrNull { kind(it) == "metadata" }
            ?.get("iterations")
            ?.jsonPrimitive
            ?.intOrNull
        val output = requireNotNull(file.parent)
        val hostManifestPath = output.resolve("manifest.json")
        val hostManifest = if (Files.isRegularFile(hostManifestPath, NOFOLLOW_LINKS)) {
            Json.parseToJsonElement(Files.readString(hostManifestPath)) as? JsonObject
                ?: error("invalid source-bound host manifest")
        } else {
            null
        }
        val comparisonRequested = hostManifest
            ?.get("configuration")
            ?.jsonObject
            ?.get("benchmark_java_options")
            ?.jsonPrimitive
            ?.content
            ?.contains("-Dmazewall.tier-e.sustained-comparison=true") == true
        check(!comparisonRequired || comparisonRequested) { "predeclared comparison profile or host manifest is missing" }
        val forks = rows.filter { kind(it) == "fork-output" }
        if (preGateRequired) {
            forks.forEach { fork ->
                TierETrustworthyPreGateExport.verify(output, fork, expectedOperations)
            }
        }
        if (comparisonRequested) {
            forks.forEach { fork ->
                TierETrustworthyComparisonVerifier.verify(output, fork, expectedOperations, requireNotNull(hostManifest))
            }
        }
        return true
    }
}
