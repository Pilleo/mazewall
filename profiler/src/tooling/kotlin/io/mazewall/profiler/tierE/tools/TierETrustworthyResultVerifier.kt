package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/** Require an intact benchmark JSONL before retiring the source artifact volume. */
public object TierETrustworthyResultVerifier {
    public fun verify(file: Path): Boolean {
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
        return true
    }
}
