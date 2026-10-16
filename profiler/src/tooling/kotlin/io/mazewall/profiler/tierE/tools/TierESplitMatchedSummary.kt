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

/** Short matched diagnostic medians; instrumented phase counters never enter workload medians. */
public object TierESplitMatchedSummary {
    private fun JsonObject.field(key: String): JsonElement = get(key) ?: JsonNull

    private fun JsonElement.number(): Long? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

    private fun JsonElement.word(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.instrumented(): Boolean = field("instrumented").toString() == "true"

    private fun JsonObject.complete(): Boolean = field("fork_complete").toString() == "true"

    private fun durations(row: JsonObject): List<Long> =
        (row.field("workload_ns") as? JsonArray)
            ?.map { value ->
        value.number() ?: throw IllegalArgumentException("Invalid measured workload duration")
    }.orEmpty()

    private fun median(values: List<Long>): JsonElement =
        values
            .sorted()
            .takeIf(List<Long>::isNotEmpty)
        ?.let { JsonPrimitive(it[it.size / 2]) } ?: JsonNull

    public fun summarize(
        dir: Path,
        failures: Int,
        sourceChanged: Int,
    ): JsonObject {
        val rows = dir.resolve("fork-results.jsonl").let { file ->
            if (!Files.exists(file)) {
                emptyList()
            } else {
                Files.readAllLines(file).filter(String::isNotBlank).map { line ->
                Json.parseToJsonElement(line) as? JsonObject ?: throw IllegalArgumentException("Malformed Tier E short fork row")
            }
            }
        }
        val baseline = rows.filter { it.field("mode").word() == "baseline" }.flatMap { durations(it) }.sorted()
        val calibrated = baseline.isNotEmpty() &&
            baseline[baseline.size / 2] >= 100_000_000L &&
            rows.flatMap { durations(it) }.all { it <= 15_000_000_000L }
        val groups = rows.groupBy { it.field("mode").word() ?: throw IllegalArgumentException("Missing fork mode") }
        return buildJsonObject {
            put("schema_version", 3)
            put("forks", rows.size)
            put("failed_forks", failures)
            put(
                "terminal",
                buildJsonObject {
                put("success", rows.isNotEmpty() && sourceChanged == 0 && rows.all { it.complete() })
                put("source_state_unchanged", sourceChanged == 0)
                put("failed_forks", failures)
                put(
                    "incomplete_forks",
                    JsonArray(
                        rows.filterNot { it.complete() }.map { row ->
                    buildJsonObject {
                    put("fork", row.field("fork"))
                    put("mode", row.field("mode"))
                    put("reason", row.field("incomplete_reason"))
                }
                },
                    ),
                )
            },
            )
            put("calibration_target", if (calibrated) "met" else "unmet")
            put(
                "modes",
                buildJsonObject {
                for ((mode, group) in groups) {
                    put(
                        mode,
                        buildJsonObject {
                    fun metric(name: String) = median(group.mapNotNull { it.field(name).number() })

                    fun instrumented(
                        groupName: String,
                        name: String,
                    ) = median(
                        group.filter { it.instrumented() }.mapNotNull { row ->
                        ((row.field(groupName) as? JsonObject)?.get(name) ?: JsonNull).number()
                    },
                    )
                    put("forks", group.size)
                    put("complete_forks", group.count { it.complete() })
                    put("instrumented_complete_forks", group.count { it.instrumented() && it.complete() })
                    put("measured_batch_median_ns", median(group.filterNot { it.instrumented() }.flatMap { durations(it) }))
                    put("target_cpu_median_ns", metric("target_cpu_ns"))
                    put("collector_cpu_median_ns", metric("collector_cpu_ns"))
                    put("target_peak_rss_median_kb", metric("target_peak_rss_kb"))
                    put("collector_peak_rss_median_kb", metric("collector_peak_rss_kb"))
                    for ((field, key) in listOf(
                        "publication_bytes_median" to "publication_bytes",
                            "publication_latency_ns_median" to "publication_latency_ns",
                        "publication_cpu_ns_median" to "publication_cpu_ns",
                            "serialization_ns_median" to "serialization_ns",
                        "peak_retained_bytes_median" to "peak_retained_bytes",
                            "native_retained_bytes_median" to "native_retained_bytes",
                    )) {
                        put(field, instrumented("native_phases", key))
                    }
                    for ((field, key) in listOf(
                        "receipt_p50_upper_ns_median" to "receipt_p50_upper_ns",
                            "receipt_p95_upper_ns_median" to "receipt_p95_upper_ns",
                        "receipt_p99_upper_ns_median" to "receipt_p99_upper_ns",
                            "receipt_p99_fine_upper_ns_median" to "receipt_p99_fine_upper_ns",
                        "receipt_samples_median" to "receipt_samples",
                    )) {
                        put(field, instrumented("collector_receipt", key))
                    }
                },
                    )
                }
            },
            )
            put("policy_eligibility", false)
        }
    }
}
