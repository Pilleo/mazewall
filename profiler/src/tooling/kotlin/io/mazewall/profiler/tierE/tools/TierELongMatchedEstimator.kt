package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/** Fork-paired, seeded diagnostic estimator. It is not a promotion gate. */
public object TierELongMatchedEstimator {
    private val json = Json
    private val modes = listOf("full-stream", "unique-edge")
    private val variants = listOf("v1", "v2", "hybrid")
    private val arms = listOf("baseline") + modes.flatMap { mode -> variants.map { "dictionary-$mode-$it" } }
    private val identityFields = listOf("source_digest", "image_id", "native_so_sha256", "daemon_jars_sha256")
    private const val PAIRS = 15
    private const val OPERATIONS = 75_000L

    public fun summarize(input: Path): JsonObject {
        val incomplete = mutableListOf<JsonObject>()
        val rows = try {
            Files.readAllLines(input).filter { it.isNotBlank() }.mapIndexedNotNull { index, raw ->
                try {
                    json.parseToJsonElement(raw) as? JsonObject ?: error("expected object")
                } catch (failure: Exception) {
                    incomplete += issue("bad_row", field = index.toString())
                    null
                }
            }
        } catch (failure: Exception) {
            incomplete += issue("jsonl_unreadable")
            emptyList()
        }
        for (field in identityFields) {
            val identities = rows.map { it.text(field) }.toSet()
            if (identities.size > 1) incomplete += issue("source_identity_mismatch", field = field)
            if (identities.any { it.isNullOrBlank() }) incomplete += issue("source_identity_missing", field = field)
        }
        val byFork = rows.groupBy { it.number("fork") }
        for (row in rows) {
            val fork = row.number("fork")
            if (fork == null || fork !in 1..PAIRS) incomplete += issue("unexpected_fork", fork = fork?.toInt(), arm = row.text("arm"))
        }
        val valid = linkedMapOf<Int, Pair<Long, Map<String, Long>>>()
        for (fork in 1..PAIRS) {
            val forkRows = byFork[fork.toLong()].orEmpty()
            if (forkRows.isEmpty()) {
                incomplete += issue("missing_fork", fork)
                continue
            }
            val candidates = forkRows.associateBy { it.text("arm") }
            if (forkRows.size != 7 || candidates.keys != arms.toSet()) {
                for (row in forkRows) {
                    if (row.text("arm") !in arms) incomplete += issue("unexpected_arm", fork, row.text("arm"))
                }
                for (arm in arms) {
                    if (arm !in candidates) incomplete += issue("missing_arm", fork, arm)
                }
                if (forkRows.size != candidates.size) incomplete += issue("duplicate_arm", fork)
                continue
            }
            val baseline = candidates.getValue("baseline")
            val localErrors = mutableListOf<JsonObject>()
            for (arm in arms) {
                val row = candidates.getValue(arm)
                if (row.number("seed") != 23062L + fork) localErrors += issue("seed_drift", fork, arm)
                if (row.number("declared_operations") != OPERATIONS) localErrors += issue("operations_mismatch", fork, arm)
                if (row.number("status") != 0L) localErrors += issue("status_failed", fork, arm)
                if (row.boolean("timed_out") != false) localErrors += issue("timed_out", fork, arm)
                if (row.boolean("policy_eligibility") != false) localErrors += issue("policy_eligibility_violated", fork, arm)
                if (row["artifact_errors"] != JsonArray(emptyList())) localErrors += issue("artifact_errors", fork, arm)
                if (row.text("target_sample_boundary") != "after_workload_before_exit") {
                    localErrors += issue("boundary_changed", fork, arm)
                }
                if (!positive(row.number("target_cpu_ns"))) {
                    localErrors += issue(cpuIssue(row["target_cpu_ns"]), fork, arm)
                }
                if (arm == "baseline") {
                    if (row.text("coverage_verdict") != "NOT_APPLICABLE") localErrors += issue("coverage_failed", fork, arm)
                    if (row["collector_cpu_ns"] != JsonNull ||
                        row["collector_sample_boundary"] != JsonNull ||
                        row["terminal_integrity"] != JsonNull
                    ) {
                        localErrors += issue("baseline_collector_present", fork, arm)
                    }
                } else {
                    if (row.text("coverage_verdict") != "PASS" || row.number("covered_paths") != 72L) {
                        localErrors += issue("coverage_failed", fork, arm)
                    }
                    if (row.text("terminal_integrity") != "COMPLETE") localErrors += issue("terminal_incomplete", fork, arm)
                    if (row.text("collector_sample_boundary") != "after_terminal_report_before_shutdown") {
                        localErrors += issue("boundary_changed", fork, arm)
                    }
                    if (!positive(row.number("collector_cpu_ns"))) {
                        localErrors += issue(cpuIssue(row["collector_cpu_ns"]), fork, arm)
                    }
                }
            }
            for (field in identityFields) {
                if (forkRows.map { it.text(field) }.toSet().size != 1) localErrors += issue("source_identity_mismatch", fork, field = field)
            }
            val baselineCpu = baseline.number("target_cpu_ns")
            if (!positive(baselineCpu)) localErrors += issue("nonpositive_baseline_target", fork, "baseline")
            incomplete += localErrors
            if (localErrors.isEmpty()) {
                valid[fork] = baselineCpu!! to arms.drop(1).associateWith { arm ->
                    candidates.getValue(arm).number("target_cpu_ns")!! + candidates.getValue(arm).number("collector_cpu_ns")!!
                }
            }
        }
        var randomState = 0x4d5a2026

        fun randomIndex(length: Int): Int {
            randomState = randomState xor (randomState shl 13)
            randomState = randomState xor (randomState ushr 17)
            randomState = randomState xor (randomState shl 5)
            return ((randomState.toLong() and 0xffff_ffffL).toDouble() / 4_294_967_296.0 * length).toInt()
        }

        fun upper95(values: List<Double>): Double {
            val draws = List(10_000) {
                median(List(values.size) { values[randomIndex(values.size)] })
            }.sorted()
            return draws[(0.975 * (draws.size - 1)).toInt()]
        }

        fun metric(
            values: List<Double>,
            reasons: List<String> = emptyList(),
        ): JsonObject {
            val failReasons = reasons.toMutableList()
            if (values.size < PAIRS && "nonpositive_v1_denominator" !in failReasons) failReasons += "insufficient_pairs"
            if (values.isEmpty() && failReasons.isEmpty()) failReasons += "no_pairs"
            if (incomplete.isNotEmpty() && "invalid_run" !in failReasons) failReasons += "invalid_run"
            val complete = failReasons.isEmpty() && values.size == PAIRS && values.all { it.isFinite() }
            return buildJsonObject {
                put("pairs", values.size)
                put("median", if (values.isEmpty()) JsonNull else JsonPrimitive(median(values)))
                put("upper_95", if (complete) JsonPrimitive(upper95(values)) else JsonNull)
                put("status", if (complete) "COMPLETE" else "INCONCLUSIVE")
                put("reasons", JsonArray(failReasons.map(::JsonPrimitive)))
            }
        }
        val results = linkedMapOf<String, JsonObject>()
        for (mode in modes) {
            val combined = variants.associateWith { variant ->
                valid.values.map { (base, profiled) -> profiled.getValue("dictionary-$mode-$variant").toDouble() / base }
            }
            val incremental = variants.associateWith { variant ->
                valid.values.map { (base, profiled) -> (profiled.getValue("dictionary-$mode-$variant") - base).toDouble() }
            }
            val hybridOverV1 = mutableListOf<Double>()
            val denominatorReasons = mutableListOf<String>()
            for ((fork, pair) in valid) {
                val (baseline, profiled) = pair
                val denominator = profiled.getValue("dictionary-$mode-v1") - baseline
                if (denominator <= 0L) {
                    incomplete += issue("nonpositive_v1_denominator", fork, "dictionary-$mode-v1")
                    if ("nonpositive_v1_denominator" !in denominatorReasons) denominatorReasons += "nonpositive_v1_denominator"
                } else {
                    hybridOverV1 += (profiled.getValue("dictionary-$mode-hybrid") - baseline).toDouble() / denominator
                }
            }

            fun group(values: Map<String, List<Double>>): JsonObject =
                buildJsonObject {
                for (variant in variants) put(variant, metric(values.getValue(variant)))
            }
            results[mode] = buildJsonObject {
                put("pairs", buildJsonObject { for (arm in listOf("baseline") + variants) put(arm, valid.size) })
                put("complete_forks", JsonArray(valid.keys.map(::JsonPrimitive)))
                put("combined_cpu_ratio", group(combined))
                put("incremental_cpu_ns", group(incremental))
                put("hybrid_over_v1_incremental_ratio", metric(hybridOverV1, denominatorReasons))
            }
        }
        // A late denominator failure invalidates bounds that may have been
        // computed for the first emission. Both emissions share one run verdict.
        val modesOutput = if (incomplete.isEmpty()) {
            results
        } else {
            results.mapValues { (_, value) ->
            fun invalidate(metric: JsonObject): JsonObject =
                buildJsonObject {
                for ((key, field) in metric) {
                    put(
                        key,
                        when (key) {
                    "upper_95" -> JsonNull
                    "status" -> JsonPrimitive("INCONCLUSIVE")
                    "reasons" -> JsonArray(((field as JsonArray).map { it.jsonPrimitive.content } + "invalid_run").distinct().map(::JsonPrimitive))
                    else -> field
                },
                    )
                }
            }
            buildJsonObject {
                for ((key, field) in value) {
                    put(
                        key,
                        when (key) {
                    "combined_cpu_ratio", "incremental_cpu_ns" -> buildJsonObject {
                        for ((variant, m) in field as JsonObject) put(variant, invalidate(m as JsonObject))
                    }
                    "hybrid_over_v1_incremental_ratio" -> invalidate(field as JsonObject)
                    else -> field
                },
                    )
                }
            }
        }
        }
        return buildJsonObject {
            put("schema_version", 1)
            put("bootstrap_resamples", 10_000)
            put("bootstrap_seed", "0x4d5a2026")
            put("paired_unit", "fork")
            put("expected_forks", PAIRS)
            put("declared_operations", OPERATIONS)
            put("rows", rows.size)
            put("complete_forks", valid.size)
            put("incomplete", JsonArray(incomplete))
            put(
                "incomplete_reasons",
                JsonArray(
                    incomplete
                .mapNotNull { it.text("reason") }
                .distinct()
                .sorted()
                .map(::JsonPrimitive),
                ),
            )
            put("modes", buildJsonObject { for ((mode, value) in modesOutput) put(mode, value) })
            put("metrics_status", if (incomplete.isEmpty() && valid.size == PAIRS) "COMPLETE" else "INCONCLUSIVE")
            put("existing_gates_note", "diagnostic only; does not replace the combined CPU 1.25 or receipt-p99 gates")
            put("prior_adoption_decision", "REJECT_DEFAULT_PROMOTION")
            put("policy_eligibility", false)
        }
    }

    private fun median(values: List<Double>): Double {
        val ordered = values.sorted()
        val middle = ordered.size / 2
        return if (ordered.size % 2 == 0) (ordered[middle - 1] + ordered[middle]) / 2.0 else ordered[middle]
    }

    private fun issue(
        reason: String,
        fork: Int? = null,
        arm: String? = null,
        field: String? = null,
    ): JsonObject =
        buildJsonObject {
        if (fork != null) put("fork", fork)
        if (arm != null) put("arm", arm)
        put("reason", reason)
        if (field != null) put("field", field)
    }

    private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.number(name: String): Long? = (get(name) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

    private fun JsonObject.boolean(name: String): Boolean? =
        when (get(name)?.toString()) {
        "true" -> true
        "false" -> false
        else -> null
    }

    private fun positive(number: Long?): Boolean = number != null && number in 1..9_007_199_254_740_991L

    private fun cpuIssue(value: kotlinx.serialization.json.JsonElement?): String =
        when {
        value == null || value == JsonNull -> "cpu_missing"
        (value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.let { it < 0 } == true -> "negative_cpu"
        else -> "cpu_not_finite"
    }
}
