package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/** Fault-matrix row and discovery gate; separate from long matched-control acceptance. */
public object TierEHybridFaultRows {
    private fun JsonObject?.field(key: String): JsonElement = this?.get(key) ?: JsonNull

    private fun JsonElement.number(): Long? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

    private fun JsonElement.word(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    public fun row(
        directory: Path,
        fork: Int,
        mode: String,
        status: Int,
    ): JsonObject {
        fun read(name: String): JsonObject? =
            directory.resolve(name).let { file ->
            if (Files.exists(file)) {
                Json.parseToJsonElement(Files.readString(file)) as? JsonObject
                ?: throw IllegalArgumentException("Malformed Tier E fault artifact: $file")
            } else {
                null
            }
        }
        val terminal = read("terminal-report.json")
        val verdict = read("coverage-verdict.json")
        val manifest = read("coverage-manifest.json")
        val recovery = terminal.field("recovery_evidence") as? JsonObject
        val singletons = (manifest.field("edges") as? JsonArray)
            ?.filter { edge ->
            val id = (edge as? JsonObject).field("workload_path").number()
            id != null && id in 48L..71L
        }.orEmpty()
        val complete = status == 0 &&
            verdict.field("coverage_verdict").word() == "PASS" &&
            terminal.field("evidence_integrity").word() == "INCOMPLETE" &&
            terminal.field("transport_completeness").toString() == "false" &&
            recovery.field("recovery_verdict").word() == "COMPLETE" &&
            singletons.size == 24 &&
            singletons.all { (it as JsonObject).field("discovery_source").word() == "map" }
        return buildJsonObject {
            put("fork", fork)
            put("mode", mode)
            put("status", status)
            put("complete", complete)
            put("recovered_edges", verdict.field("recovered_edges"))
            for (key in listOf(
                "confirmed_ring_loss_recoveries",
                    "discovery_samples",
                    "discovery_p99_upper_ns",
                "dictionary_waiting_max_ns",
                    "native_live_publications",
            )) {
                    put(key, recovery.field(key))
                }
            put("policy_eligibility", false)
        }
    }

    public fun summary(
        directory: Path,
        failures: Int,
        sourceChanged: Int,
    ): JsonObject {
        val rowsFile = directory.resolve("fork-results.jsonl")
        val rows = Files.readAllLines(rowsFile).filter(String::isNotBlank).map { line ->
            Json.parseToJsonElement(line) as? JsonObject ?: throw IllegalArgumentException("Malformed Tier E fault fork row")
        }
        val allRecovered = rows.isNotEmpty() && rows.all { it.field("complete").toString() == "true" }
        val p99 = rows.isNotEmpty() &&
            rows.all { row ->
            (row.field("discovery_samples").number() ?: 0L) > 0 &&
                (row.field("discovery_p99_upper_ns").number() ?: Long.MAX_VALUE) <= 500_000_000L
        }
        return buildJsonObject {
            put("schema_version", 1)
            put("forks", rows.size)
            put("failed_forks", failures)
            put("source_state_unchanged", sourceChanged == 0)
            put("all_singletons_recovered", allRecovered)
            put("discovery_p99_under_500ms", p99)
            put("policy_eligibility", false)
        }
    }
}
