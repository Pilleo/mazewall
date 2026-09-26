package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/** Process-bound external perf evidence; never infer stack attribution from unrelated PID samples. */
public object TierETrustworthyPerfEvidence {
    public fun write(
        directory: Path,
        requireJitMaps: Boolean = false,
    ) {
        val rows = Files
            .readAllLines(directory.resolve("results.jsonl"))
            .filter(String::isNotBlank)
            .map { line ->
            Json.parseToJsonElement(line) as? JsonObject ?: throw IllegalArgumentException("Invalid Tier E perf result row")
        }.filter { it["kind"] == JsonPrimitive("fork-output") }
        require(rows.isNotEmpty()) { "No perf fork outputs" }
        val identities = rows.map { row ->
            val text = row["output"]?.jsonPrimitive?.content ?: error("Missing fork output")
            val variant = row.getValue("variant").jsonPrimitive.content
            val target = row["target_pid"]
                ?.jsonPrimitive
                ?.content
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?: throw IllegalArgumentException("Missing benchmark target PID")
            val pair = Regex("collector_pid=([0-9]+) workload_pid=([0-9]+)").find(text)
            buildJsonObject {
                put("variant", row.getValue("variant"))
                put("fork", row.getValue("fork"))
                if (variant == "baseline" || variant.startsWith("user_notif")) {
                    require(pair == null) { "Unexpected collector PID for an unprofiled benchmark arm" }
                    put("target_pid", target)
                } else {
                    require(pair != null) { "Missing collector and workload PID identity" }
                    val collector = pair.groupValues[1].toLong()
                    val workload = pair.groupValues[2].toLong()
                    require(collector == target && workload > 0 && workload != collector) {
                        "Mismatched Tier E collector or workload process identity"
                    }
                    put("collector_pid", collector)
                    put("workload_pid", workload)
                }
            }
        }
        Files.writeString(directory.resolve("perf-processes.json"), JsonArray(identities).toString() + "\n")
        val samples = mutableMapOf<Long, Int>()
        val sampleLine = Regex("^\\S+\\s+([0-9]+)/[0-9]+\\s+")
        for (line in Files.readAllLines(directory.resolve("perf-pid-tid-samples.txt"))) {
            sampleLine.find(line)?.groupValues?.get(1)?.toLongOrNull()?.let { pid ->
                samples[pid] = samples.getOrDefault(pid, 0) + 1
            }
        }
        Files.writeString(
            directory.resolve("perf-pid-sample-counts.json"),
            JsonArray(
                samples.entries.sortedBy { it.key }.map { (pid, count) ->
            buildJsonObject {
                put("pid", pid)
                put("samples", count)
            }
        },
            ).toString() +
            "\n",
        )
        val entries = identities.flatMap { identity ->
            val roles = if ("target_pid" in identity) {
                listOf("target" to identity.getValue("target_pid"))
            } else {
                listOf("collector" to identity.getValue("collector_pid"), "workload" to identity.getValue("workload_pid"))
            }
            roles.map { (role, id) ->
                val pid = id.jsonPrimitive.content.toLong()
                buildJsonObject {
                    put("variant", identity.getValue("variant"))
                    put("fork", identity.getValue("fork"))
                    put("role", role)
                    put("pid", pid)
                    put("samples", samples.getOrDefault(pid, 0))
                }
            }
        }
        Files.writeString(
            directory.resolve("perf-process-attribution.json"),
            buildJsonObject {
            put("schema_version", 1)
            put("measurement", "external_user_cycles_by_retained_process_identity")
            put("entries", JsonArray(entries))
        }.toString() +
            "\n",
        )
        if (requireJitMaps) {
            val mapIds = rows
                .flatMap { row ->
                val text = row["output"]?.jsonPrimitive?.content.orEmpty()
                val target = row["target_pid"]
                    ?.jsonPrimitive
                    ?.content
                    ?.toLongOrNull()
                    ?.let(::listOf)
                    .orEmpty()
                target +
                    Regex("(?:collector|workload)_pid=([0-9]+)|tier-e-perf-window: pid=([0-9]+)")
                        .findAll(text)
                    .mapNotNull {
                        it.groupValues
                        .drop(1)
                        .firstOrNull(String::isNotBlank)
                        ?.toLongOrNull()
                    }.toList()
            }.distinct()
                .sorted()
            Files.writeString(directory.resolve("perf-map-required-pids.txt"), mapIds.joinToString("\n", postfix = "\n"))
            for (pid in mapIds) {
                require(Files.size(directory.resolve("perf-maps/perf-$pid.map")) > 0) { "Missing JIT perf map for PID $pid" }
            }
        }
    }
}
