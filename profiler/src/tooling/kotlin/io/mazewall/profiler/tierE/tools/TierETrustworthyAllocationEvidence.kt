package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/** Lower-bound libc malloc requests, keyed to retained benchmark process identity. */
public object TierETrustworthyAllocationEvidence {
    public fun write(directory: Path) {
        val rows = Files
            .readAllLines(directory.resolve("results.jsonl"))
            .filter(String::isNotBlank)
            .map { line ->
            Json.parseToJsonElement(line) as? JsonObject ?: throw IllegalArgumentException("Invalid allocation result row")
        }.filter { it["kind"] == JsonPrimitive("fork-output") }
        val totals = mutableMapOf<Long, Pair<Long, Long>>()
        val entry = Regex("^\\s*\\S+\\s+([0-9]+).*size=([0-9]+)")
        for (line in Files.readAllLines(directory.resolve("allocation-events.txt"))) {
            val match = entry.find(line) ?: continue
            val pid = match.groupValues[1].toLong()
            val size = match.groupValues[2].toLong()
            val prior = totals[pid] ?: 0L to 0L
            totals[pid] = Math.addExact(prior.first, 1) to Math.addExact(prior.second, size)
        }
        val allocations = totals.entries.sortedBy { it.key }.map { (pid, values) ->
            buildJsonObject {
                put("pid", pid)
                put("allocation_count", values.first)
                put("requested_bytes", values.second)
            }
        }
        Files.writeString(directory.resolve("allocation-by-pid.json"), JsonArray(allocations).toString() + "\n")
        val summaries = allocations.map { allocation ->
            val fork = rows.firstOrNull { it["target_pid"] == allocation["pid"] }
            buildJsonObject {
                for ((key, value) in allocation) put(key, value)
                for (field in listOf("variant", "fork", "workload_correctness", "whole_session_evidence")) {
                    put(field, fork?.get(field) ?: JsonNull)
                }
            }
        }
        Files.writeString(
            directory.resolve("allocation-summary.json"),
            buildJsonObject {
            put("schema_version", 1)
            put("measurement", "external_libc_malloc_requested_bytes")
            put("coverage", "lower_bound_malloc_only")
            put("entries", JsonArray(summaries))
        }.toString() +
            "\n",
        )
    }
}
