package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.nio.file.Path

/** Independent Kotlin acceptance logic; never delegates to the estimator. */
public object TierELongMatchedAcceptance {
    private val json = Json
    private val arms = listOf(
        "baseline",
        "dictionary-full-stream-v1",
        "dictionary-full-stream-v2",
        "dictionary-full-stream-hybrid",
        "dictionary-unique-edge-v1",
        "dictionary-unique-edge-v2",
        "dictionary-unique-edge-hybrid",
    )
    private val identities = listOf("source_digest", "image_id", "native_so_sha256", "daemon_jars_sha256")
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

    public data class Result(
        val verdict: String,
        val errors: List<String>,
        val rows: Int,
        val baselines: Int,
        val profiled: Int,
    )

    public fun verifyJsonl(
        jsonl: Path,
        manifestFile: Path,
        sourceBeforeFile: Path,
        sourceAfterFile: Path,
        terminalFile: Path,
    ): Result {
        val errors = mutableListOf<String>()

        fun document(file: Path): JsonObject? =
            try {
            json.parseToJsonElement(Files.readString(file)) as? JsonObject
                ?: error("expected JSON object")
        } catch (failure: Exception) {
            errors += "${file.fileName}: ${failure.message}"
            null
        }
        val manifest = document(manifestFile)
        val before = document(sourceBeforeFile)
        val after = document(sourceAfterFile)
        val terminal = document(terminalFile)
        val rows = try {
            Files.readAllLines(jsonl).filter { it.isNotBlank() }.mapIndexedNotNull { index, line ->
                try {
                    json.parseToJsonElement(line) as? JsonObject ?: error("expected JSON object")
                } catch (failure: Exception) {
                    errors += "row_$index: ${failure.message}"
                    null
                }
            }
        } catch (failure: Exception) {
            errors += "jsonl_unreadable: ${failure.message}"
            emptyList()
        }
        if (rows.size != 105) errors += "row_count: expected 105, observed ${rows.size}"
        if (terminal != null &&
            (
                terminal.boolean("success") != true ||
                    terminal.boolean("binaries_unchanged") != true ||
                    terminal.boolean("source_state_unchanged") != true ||
                    terminal.boolean("policy_eligibility") != false ||
                    terminal.number("forks") != 15L ||
                terminal.number("arms") != 105L
            )
        ) {
                errors += "terminal_not_complete"
            }
        if (before != null && after != null) {
            if (before.text("revision").isNullOrBlank() || before.text("revision") != after.text("revision")) {
                errors += "source_revision_mismatch"
            }
            if (before["files"] !is JsonObject || before["files"] != after["files"]) {
                errors += "source_files_drift"
            }
            if (before.number("input_dirty_count") != 0L ||
                after.number("input_dirty_count") != 0L ||
                after.boolean("unchanged") != true
            ) {
                errors += "source_state_dirty_or_changed"
            }
            val count = (before["files"] as? JsonObject)?.size?.toLong()
            if (count == null || before.number("source_file_count") != count || after.number("source_file_count") != count) {
                errors += "source_file_count_mismatch"
            }
        }
        for (field in identities) {
            val values = rows.map { it.text(field) }.toSet()
            if (values.size != 1 ||
                values.singleOrNull().isNullOrBlank() ||
                manifest?.text(field) !in values
            ) {
                errors += "identity_not_uniform: $field"
            }
        }
        if (before?.text("source_digest") != manifest?.text("source_digest") ||
            after?.text("source_digest") != manifest?.text("source_digest")
        ) {
            errors += "source_provenance_unbound: source_digest"
        }
        if (manifest?.number("forks") != 15L ||
            manifest.number("arms_per_fork") != 7L ||
            manifest.number("declared_operations") != 75_000L ||
            manifest.boolean("policy_eligibility") != false
        ) {
            errors += "manifest_mismatch"
        }

        val byFork = rows.groupBy { it.number("fork") }
        var baselines = 0
        var profiled = 0
        for (fork in 1..15) {
            val forkRows = byFork[fork.toLong()].orEmpty()
            if (forkRows.map { it.text("arm") }.sortedBy { it.orEmpty() } != arms.sorted()) {
                errors += "fork_${fork}_arms"
                continue
            }
            for (row in forkRows) {
                val arm = row.text("arm")!!
                if (row.number("seed") != 23062L + fork ||
                    row.number("declared_operations") != 75_000L ||
                    row.number("status") != 0L ||
                    row.boolean("timed_out") != false ||
                    row.boolean("policy_eligibility") != false ||
                    row["artifact_errors"] != JsonArray(emptyList()) ||
                    !positiveCpu(row["target_cpu_ns"]) ||
                    row.text("target_sample_boundary") != "after_workload_before_exit"
                ) {
                    errors += "fork_${fork}_${arm}_invalid_row"
                }
                if (arm == "baseline") {
                    baselines++
                    if (row["collector_cpu_ns"] != JsonNull ||
                        row["collector_sample_boundary"] != JsonNull ||
                        row["terminal_integrity"] != JsonNull ||
                        row.text("coverage_verdict") != "NOT_APPLICABLE" ||
                        row["covered_paths"] != JsonNull
                    ) {
                        errors += "fork_${fork}_baseline_collector_present"
                    }
                } else {
                    profiled++
                    if (!positiveCpu(row["collector_cpu_ns"]) ||
                        row.text("collector_sample_boundary") != "after_terminal_report_before_shutdown" ||
                        row.text("coverage_verdict") != "PASS" ||
                        row.number("covered_paths") != 72L ||
                        row.text("terminal_integrity") != "COMPLETE"
                    ) {
                        errors += "fork_${fork}_${arm}_profiled_incomplete"
                    }
                }
            }
        }
        if (byFork.keys != (1L..15L).toSet()) errors += "fork_coverage"
        if (baselines != 15 || profiled != 90) errors += "arm_counts"
        return Result(if (errors.isEmpty()) "PASS" else "INCOMPLETE", errors, rows.size, baselines, profiled)
    }

    private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.number(name: String): Long? = (get(name) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

    private fun JsonObject.boolean(name: String): Boolean? =
        when (get(name)?.toString()) {
        "true" -> true
        "false" -> false
        else -> null
    }

    private fun positiveCpu(value: JsonElement?): Boolean {
        val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: return false
        return number in 1..MAX_SAFE_INTEGER
    }
}
