package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Saved Tier E coverage experiment: failures and absent measurements never become zero-valued successes. */
public object TierECoverageSummary {
    private val variants = listOf("dictionary_full_stream", "dictionary_unique_edge", "inline_full_stream", "inline_unique_edge")
    private val metrics = listOf(
        "workload_total_ns",
        "elapsed_ns",
        "cpu_ns",
        "peak_rss_bytes",
        "missing_operations",
        "matched_operations",
        "expected_operations",
        "wrong_operations",
        "unresolved_operations",
        "acknowledged_operations",
        "suppressed_operations",
        "lost",
        "dropped",
    )

    public fun parse(text: String): List<JsonObject> {
        val lines = text.split('\n').let { if (text.endsWith('\n')) it.dropLast(1) else it }
        return buildList {
            for ((index, line) in lines.withIndex()) {
                try {
                    add(Json.parseToJsonElement(line) as? JsonObject ?: error("Coverage JSONL row is not an object"))
                } catch (failure: Exception) {
                    if (index != lines.lastIndex || text.endsWith('\n')) throw failure
                    add(
                        buildJsonObject {
                        put("kind", "failure")
                        put("reason", "interrupted_jsonl_tail")
                        put("line", index + 1)
                        put("derived_by_summary", true)
                    },
                    )
                    add(
                        buildJsonObject {
                        put("kind", "terminal")
                        put("success", false)
                        put("reason", "interrupted_jsonl_tail")
                        put("derived_by_summary", true)
                    },
                    )
                }
            }
        }
    }

    private fun JsonObject?.text(key: String): String? = (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject?.number(key: String): Double? = (this?.get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull

    private fun JsonObject?.array(key: String): JsonArray? = this?.get(key) as? JsonArray

    private fun pairs(
        outputs: List<JsonObject>,
        coverage: List<JsonObject>,
        forks: List<Int>,
    ): JsonObject =
        buildJsonObject {
        for (metric in listOf("cpu_ns", "peak_rss_bytes", "elapsed_ns")) {
            val values = forks.mapNotNull { fork ->
                val full = outputs.find { it.text("variant") == "dictionary_full_stream" && it.number("fork") == fork.toDouble() }
                val unique = outputs.find { it.text("variant") == "dictionary_unique_edge" && it.number("fork") == fork.toDouble() }

                fun eligible(row: JsonObject?): Boolean =
                    row?.text("whole_session_evidence") == "COMPLETE" &&
                    (row.number(metric) ?: 0.0) > 0 &&
                    coverage.any {
                        it.text("variant") == row.text("variant") &&
                        it.number("fork") == fork.toDouble() &&
                        (it["report"] as? JsonObject).text("coverage_verdict") == "PASS"
                    }
                if (!eligible(full) || !eligible(unique)) return@mapNotNull null
                val fullValue = requireNotNull(full).number(metric)!!
                val uniqueValue = requireNotNull(unique).number(metric)!!
                buildJsonObject {
                    put("fork", fork)
                    put("full_stream", full.getValue(metric))
                    put("unique_edge", unique.getValue(metric))
                    put("change_percent", (uniqueValue / fullValue - 1) * 100)
                }
            }
            val changes = values.map { it.number("change_percent")!! }
            val interval = if (changes.size >= 5) {
                var state = 0x54494552
                val samples = List(10_000) {
                    List(changes.size) {
                        state = state * 1_664_525 + 1_013_904_223
                        changes[((state.toLong() and 0xffff_ffffL) * changes.size / 4_294_967_296L).toInt()]
                    }.average()
                }.sorted()
                JsonArray(listOf(JsonPrimitive(samples[249]), JsonPrimitive(samples[9749])))
            } else {
                JsonNull
            }
            put(
                metric,
                buildJsonObject {
                put("pairs", JsonArray(values))
                put("mean_change_percent", changes.takeIf { it.isNotEmpty() }?.average()?.let(::JsonPrimitive) ?: JsonNull)
                put("confidence_interval_95_percent", interval)
                put("method", "paired unique/full percent change; deterministic 10000-resample percentile bootstrap with at least five complete pairs")
            },
            )
        }
    }

    public fun summarize(rows: List<JsonObject>): JsonObject {
        val metadata = rows.find { it.text("kind") == "metadata" }
        val coverage = rows.filter { it.text("kind") == "coverage" }
        val outputs = rows.filter { it.text("kind") == "fork-output" }
        val count = (metadata?.get("forks") as? JsonPrimitive)?.intOrNull
            ?: rows.maxOfOrNull { (it["fork"] as? JsonPrimitive)?.intOrNull ?: 0 } ?: 0
        require(count in 0..100_000) { "Invalid coverage fork count" }
        val forks = (1..count).toList()
        val terminal = rows.filter { it.text("kind") == "terminal" }
        val failures = rows.filter { it.text("kind") in setOf("failure", "fork-failure") }

        fun measurements(row: JsonObject?): JsonObject =
            buildJsonObject {
            for (metric in metrics) put(metric, row?.get(metric) ?: JsonNull)
        }
        return buildJsonObject {
            put("policy_eligibility", false)
            put("run_completed", terminal.lastOrNull()?.get("success") == JsonPrimitive(true))
            put("terminal_records", JsonArray(terminal))
            put("failures", JsonArray(failures))
            put("source_revision", metadata?.get("source_revision") ?: JsonNull)
            put("metadata", metadata ?: JsonNull)
            put(
                "measurement_contract",
                buildJsonObject {
                put("corpus", "72 declared Java call paths to openat; not all application behavior")
                put("workload_time", "paced at 1250 operations/s; not an unpaced application slowdown estimate")
                put("cpu", "benchmark child process only: workload JVM for baseline, collector JVM for Tier E; excludes Tier E workload child CPU")
                put("rss", "benchmark child process high-water RSS; not the shared retained-state budget or combined process-tree RSS")
                put("native_memory", "phases.native_retained_bytes and peak_retained_bytes are native accounting, not collector heap")
                put("discovery_time", "post-retention collector/report observation since gate; terminal fallback is an upper bound, not syscall entry time")
                put("closure", "runner completion does not imply a passing coverage or whole-session verdict")
            },
            )
            put(
                "baseline",
                JsonArray(
                    forks.map { fork ->
                val output = outputs.find { it.text("variant") == "baseline" && it.number("fork") == fork.toDouble() }
                buildJsonObject {
                    put("fork", fork)
                    put("coverage_verdict", "NOT_APPLICABLE")
                    put("workload_correctness", output.text("workload_correctness") ?: "UNVERIFIED")
                    for ((key, value) in measurements(output)) put(key, value)
                }
            },
                ),
            )
            put("paired_dictionary_resources", pairs(outputs, coverage, forks))
            put(
                "variants",
                JsonArray(
                    variants.map { variant ->
                val results = forks.map { fork ->
                    val row = coverage.find { it.text("variant") == variant && it.number("fork") == fork.toDouble() }
                    val output = outputs.find { it.text("variant") == variant && it.number("fork") == fork.toDouble() }
                    val report = row?.get("report") as? JsonObject ?: buildJsonObject {
                        put("coverage_verdict", "UNVERIFIED")
                        put("expected", JsonArray((0..71).map(::JsonPrimitive)))
                        put("recovered", JsonArray(emptyList()))
                        put("missing", JsonArray((0..71).map(::JsonPrimitive)))
                        put("discoveries", JsonArray(emptyList()))
                    }
                    val outputText = output.text("output").orEmpty()
                    val publicationFailures = Regex("definition_publication_failure=([a-z_]+)")
                        .findAll(outputText)
                        .map { it.groupValues[1] }
                        .distinct()
                        .toMutableList()
                    if ("definition publication exhausted retained-state budget" in outputText) publicationFailures += "retained_state_budget_exhausted"
                    val complete = output.text("whole_session_evidence") == "COMPLETE"
                    val affinity = Regex("tier-e-coverage-affinity: workload=(\\S+) collector=(\\S+) launched_pinned=true").find(outputText)
                    buildJsonObject {
                        put("fork", fork)
                        put("seed", row?.get("seed") ?: JsonPrimitive(fork))
                        for ((key, value) in report) put(key, value)
                        put("event_workload_correctness", output.text("workload_correctness") ?: "UNVERIFIED")
                        put("whole_session_evidence", output.text("whole_session_evidence") ?: "UNVERIFIED")
                        for ((key, value) in measurements(output)) put(key, value)
                        put("phases", output?.get("phases") ?: JsonNull)
                        put("publication_failures", JsonArray(publicationFailures.map(::JsonPrimitive)))
                        put(
                            "effective_affinity",
                            affinity?.let { match ->
                            buildJsonObject {
                            put("workload", match.groupValues[1])
                            put("collector", match.groupValues[2])
                            put("workload_launched_pinned", true)
                        }
                        } ?: JsonNull,
                        )
                        put(
                            "dimensions",
                            buildJsonObject {
                            put("capture_domain_validity", if (output?.get("operation_oracle_available") == JsonPrimitive(true)) "DECLARED_OPENAT_ONLY" else "UNVERIFIED")
                            put(
                                "transport_completeness",
                                if (complete) {
                                "COMPLETE"
                            } else if (output != null) {
                                "INCOMPLETE"
                            } else {
                                "UNVERIFIED"
                            },
                            )
                            put("attribution_coverage", report.text("coverage_verdict") ?: "UNVERIFIED")
                            put(
                                "dictionary_integrity",
                                if (complete) {
                                "COMPLETE"
                            } else if (publicationFailures.isNotEmpty()) {
                                "INCOMPLETE"
                            } else {
                                "UNVERIFIED"
                            },
                            )
                            put("lifecycle_validity", if (complete) "COMPLETE" else "UNVERIFIED")
                            put("runtime_overhead", "NOT_GATED")
                        },
                        )
                    }
                }
                val recovered = results
                    .flatMap { row -> row.array("recovered")?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty() }
                    .distinct()
                    .sorted()
                buildJsonObject {
                    put("variant", variant)
                    put("forks", JsonArray(results))
                    put("cumulative_recovered", JsonArray(recovered.map(::JsonPrimitive)))
                    put("cumulative_recall", recovered.size / 72.0)
                    put("all_forks_recover_all_paths", results.isNotEmpty() && results.all { it.text("coverage_verdict") == "PASS" })
                }
            },
                ),
            )
            put("performance_gate", "not_measured_against_comparable_baseline")
        }
    }
}
