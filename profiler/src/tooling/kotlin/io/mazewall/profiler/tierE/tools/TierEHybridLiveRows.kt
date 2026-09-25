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

/** Short live-vs-terminal publication admission and diagnostic mode aggregates. */
public object TierEHybridLiveRows {
    private fun JsonObject?.field(key: String): JsonElement = this?.get(key) ?: JsonNull

    private fun JsonElement.number(): Long? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

    private fun JsonElement.word(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    public fun row(
        dir: Path,
        fork: Int,
        mode: String,
        profile: String,
        live: Boolean,
        status: Int,
        expectedOperations: Int,
    ): JsonObject {
        val base = TierESplitForkRow.parse(fork, mode, status, dir, 10, expectedOperations)
        val terminal = dir.resolve("terminal-report.json").let { file ->
            if (Files.exists(file)) Json.parseToJsonElement(Files.readString(file)) as? JsonObject else null
        }
        val recovery = terminal.field("recovery_evidence") as? JsonObject
        val publications = recovery.field("native_live_publications")
        val verdict = recovery.field("recovery_verdict")
        val native = base.field("native_phases") as? JsonObject
        val complete = base.field("fork_complete").toString() == "true" &&
            verdict.word() == "COMPLETE" &&
            (!live || (publications.number() ?: 0L) > 0) &&
            native.field("publication_cpu_ns").number() != null
        val reason = if (!complete && base.field("incomplete_reason").word() == "complete") {
            "live_dependency_admission_incomplete"
        } else {
            base.field("incomplete_reason").word() ?: "missing_reason"
        }
        return buildJsonObject {
            for ((key, value) in base) put(key, value)
            put("profile", profile)
            put("live", live)
            put("native_live_publications", publications)
            put("recovery_verdict", verdict)
            put("fork_complete", complete)
            put("incomplete_reason", reason)
        }
    }

    public fun summary(
        dir: Path,
        failed: Int,
        sourceChanged: Int,
    ): JsonObject {
        val rows = Files.readAllLines(dir.resolve("fork-results.jsonl")).filter(String::isNotBlank).map { line ->
            Json.parseToJsonElement(line) as? JsonObject ?: throw IllegalArgumentException("Malformed Tier E live row")
        }

        fun median(values: List<Long>): JsonElement =
            values
                .sorted()
                .takeIf(List<Long>::isNotEmpty)
            ?.let { JsonPrimitive(it[it.size / 2]) } ?: JsonNull
        val groups = rows.groupBy { row ->
            "${row.field("profile").word()}:${row.field("mode").word()}:${if (row.field("live").toString() == "true") "live" else "terminal"}"
        }
        return buildJsonObject {
            put("schema_version", 1)
            put("completed", rows.size)
            put("failed_forks", failed)
            put("source_state_unchanged", sourceChanged == 0)
            put(
                "modes",
                buildJsonObject {
                for ((label, group) in groups) {
                    put(
                        label,
                        buildJsonObject {
                    put("forks", group.size)
                    put("complete", group.count { it.field("fork_complete").toString() == "true" })

                    fun fromNative(field: String) = median(group.mapNotNull { (it.field("native_phases") as? JsonObject).field(field).number() })
                    put("publication_bytes_median", fromNative("publication_bytes"))
                    put("publication_cpu_ns_median", fromNative("publication_cpu_ns"))
                    put("native_retained_peak_median", fromNative("peak_retained_bytes"))
                    put("native_live_publications_median", median(group.mapNotNull { it.field("native_live_publications").number() }))
                },
                    )
                }
            },
            )
            put("policy_eligibility", false)
        }
    }
}
