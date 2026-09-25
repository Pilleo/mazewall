package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Full-directory counterpart to the independent row verifier. */
public object TierELongMatchedDirectoryVerifier {
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
    private val dimensions = listOf(
        "capture_domain_validity",
        "transport_completeness",
        "attribution_coverage",
        "dictionary_integrity",
        "lifecycle_validity",
    )

    public fun verify(directory: Path): TierELongMatchedAcceptance.Result {
        val base = TierELongMatchedAcceptance.verifyJsonl(
            directory.resolve("fork-results.jsonl"),
            directory.resolve("run-manifest.json"),
            directory.resolve("source-state-before.json"),
            directory.resolve("source-state-after.json"),
            directory.resolve("terminal.json"),
        )
        if (base.verdict != "PASS") return base
        val errors = mutableListOf<String>()
        val rows = Files
            .readAllLines(directory.resolve("fork-results.jsonl"))
            .filter { it.isNotBlank() }
            .map { json.parseToJsonElement(it) as JsonObject }
            .associateBy { Pair(it.text("arm"), it.number("fork")) }
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) {
            return base.copy(verdict = "INCOMPLETE", errors = listOf("decision_dir_not_directory"))
        }
        Files.list(directory).use { entries ->
            entries
                .filter { Files.isDirectory(it, NOFOLLOW_LINKS) }
                .forEach { if (it.fileName.toString() !in arms) errors += "unexpected_arm_dir: ${it.fileName}" }
        }
        for (arm in arms) {
            val armDir = directory.resolve(arm)
            if (!Files.isDirectory(armDir, NOFOLLOW_LINKS)) {
                errors += "missing_arm_dir: $arm"
                continue
            }
            Files.list(armDir).use { entries ->
                entries
                    .filter { Files.isDirectory(it, NOFOLLOW_LINKS) }
                    .forEach { if (it.fileName.toString() !in (1..15).map { n -> "fork-$n" }) errors += "unexpected_fork_dir: $arm/$it" }
            }
            for (fork in 1..15) {
                val relative = "$arm/fork-$fork"
                val armPath = armDir.resolve("fork-$fork")
                if (!Files.isDirectory(armPath, NOFOLLOW_LINKS)) {
                    errors += "missing_arm_dir: $relative"
                    continue
                }
                val row = rows[Pair(arm, fork.toLong())]
                if (row == null) {
                    errors += "missing_arm_row: $relative"
                    continue
                }
                validateArm(armPath, relative, arm, fork, row, errors)
            }
        }
        return if (errors.isEmpty()) base else base.copy(verdict = "INCOMPLETE", errors = errors)
    }

    private fun validateArm(
        path: Path,
        label: String,
        arm: String,
        fork: Int,
        row: JsonObject,
        errors: MutableList<String>,
    ) {
        fun read(file: String): JsonObject? {
            val child = path.resolve(file)
            if (!Files.isRegularFile(child, NOFOLLOW_LINKS)) {
                errors += "missing_artifact: $label/$file"
                return null
            }
            return try {
                json.parseToJsonElement(Files.readString(child)) as? JsonObject
                    ?: error("expected JSON object")
            } catch (failure: Exception) {
                errors += "unreadable_artifact: $label/$file: ${failure.message}"
                null
            }
        }

        val result = read("arm-result.json")
        if (result == null ||
            result.number("fork") != fork.toLong() ||
            result.text("arm") != arm ||
            result.text("status") != "COMPLETE" ||
            result.number("fixture_exit") != 0L ||
            result.boolean("timed_out") != false ||
            result.number("seed") != 23062L + fork ||
            result.boolean("policy_eligibility") != false
        ) {
            errors += "arm_result_mismatch: $label/arm-result.json"
        }

        val oracle = read("coverage-oracle.json")
        if (oracle == null ||
            oracle.number("seed") != 23062L + fork ||
            oracle.number("declared_operations") != 75_000L ||
            oracle["paths"] !is JsonArray ||
            !validOracle(oracle["paths"] as JsonArray) ||
            oracle.number("seed") != row.number("seed") ||
            oracle.number("declared_operations") != row.number("declared_operations")
        ) {
            errors += "oracle_mismatch: $label/coverage-oracle.json"
        }

        val target = read("target-cpu.json")
        if (target == null ||
            target.number("schema_version") != 1L ||
            target.number("version") != 1L ||
            target.text("unit") != "ns" ||
            target.text("sample_boundary") != "after_workload_before_exit" ||
            !positive(target.number("target_cpu_ns")) ||
            target.number("target_cpu_ns") != row.number("target_cpu_ns") ||
            target.text("sample_boundary") != row.text("target_sample_boundary") ||
            !positive(target.number("host_pid")) ||
            !positive(target.number("starttime"))
        ) {
            errors += "target_cpu_mismatch: $label/target-cpu.json"
        }

        val summary = read("summary.json")
        if (arm == "baseline") {
            for (forbidden in listOf("terminal-report.json", "collector-process-metrics.json")) {
                if (Files.exists(path.resolve(forbidden), NOFOLLOW_LINKS)) errors += "baseline_has_profiled_artifact: $label/$forbidden"
            }
            if (summary?.text("mode") != "baseline" ||
                summary.number("target_exit") != 0L ||
                summary.boolean("policy_eligibility") != false
            ) {
                errors += "baseline_summary_mismatch: $label/summary.json"
            }
            return
        }

        val coverage = read("coverage-verdict.json")
        if (coverage == null ||
            coverage.text("coverage_verdict") != "PASS" ||
            coverage.number("expected_edges") != 72L ||
            coverage.number("recovered_edges") != 72L ||
            !emptyList(coverage["admission_errors"]) ||
            !emptyList(coverage["missing_paths"]) ||
            !emptyList(coverage["wrong_edges"]) ||
            coverage.boolean("policy_eligibility") != false ||
            coverage.text("coverage_verdict") != row.text("coverage_verdict") ||
            coverage.number("recovered_edges") != row.number("covered_paths")
        ) {
            errors += "coverage_mismatch: $label/coverage-verdict.json"
        }

        val terminal = read("terminal-report.json")
        if (terminal == null ||
            terminal.text("evidence_integrity") != "COMPLETE" ||
            terminal.text("terminal_reason") != "COMPLETE" ||
            terminal.boolean("policy_eligibility") != false ||
            dimensions.any { terminal.boolean(it) != true } ||
            !emptyList(terminal["failed_dimensions"]) ||
            terminal.text("registration_id").isNullOrBlank() ||
            !positive(terminal.number("session_epoch")) ||
            !positive(terminal.number("target_pid")) ||
            !positive(terminal.number("process_incarnation")) ||
            terminal.text("evidence_integrity") != row.text("terminal_integrity") ||
            target?.number("host_pid") != terminal.number("target_pid") ||
            target?.number("starttime") != terminal.number("process_incarnation")
        ) {
            errors += "terminal_mismatch: $label/terminal-report.json"
        }

        val collector = read("collector-process-metrics.json")
        if (collector == null ||
            collector.text("unit") != "ns" ||
            collector.text("cpu_time_basis") != "process_ticks_since_start" ||
            collector.text("sample_boundary") != "after_terminal_report_before_shutdown" ||
            !positive(collector.number("cpu_ticks")) ||
            !positive(collector.number("cpu_ns")) ||
            collector.number("cpu_ns") != row.number("collector_cpu_ns") ||
            collector.text("sample_boundary") != row.text("collector_sample_boundary")
        ) {
            errors += "collector_cpu_mismatch: $label/collector-process-metrics.json"
        }

        if (summary == null ||
            summary.boolean("policy_eligibility") != false ||
            summary["coverage"] != coverage ||
            summary["terminal"] != terminal
        ) {
            errors += "summary_mismatch: $label/summary.json"
        }
    }

    private fun validOracle(paths: JsonArray): Boolean {
        if (paths.size != 72) return false
        val seen = mutableSetOf<Long>()
        for (element in paths) {
            val entry = element as? JsonObject ?: return false
            val index = entry.number("path") ?: return false
            if (index !in 0L..71L || !seen.add(index)) return false
            val category = if (index < 24) {
                "common"
            } else if (index < 48) {
                "infrequent"
            } else {
                "singleton"
            }
            val frequency = if (index < 24) {
                3000L
            } else if (index < 48) {
                124L
            } else {
                1L
            }
            if (entry.text("category") != category ||
                entry.text("method") != "path${index.toString().padStart(2, '0')}" ||
                entry.number("frequency") != frequency ||
                entry.number("syscall_number") != 257L
            ) {
                return false
            }
        }
        return seen == (0L..71L).toSet()
    }

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.number(key: String): Long? = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

    private fun JsonObject.boolean(key: String): Boolean? =
        when (get(key)?.toString()) {
        "true" -> true
        "false" -> false
        else -> null
    }

    private fun positive(value: Long?): Boolean = value != null && value in 1..9_007_199_254_740_991L

    private fun emptyList(value: kotlinx.serialization.json.JsonElement?): Boolean = (value as? JsonArray)?.isEmpty() == true
}
