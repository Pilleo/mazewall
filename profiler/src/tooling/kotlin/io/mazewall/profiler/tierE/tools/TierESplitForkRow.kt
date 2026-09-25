package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/** Short matched fork parsing and strict completeness, with unchanged JSON row shape. */
public object TierESplitForkRow {
    public fun parse(
        fork: Int,
        mode: String,
        status: Int,
        dir: Path,
        expectedBatches: Int = 10,
        expectedOperations: Int? = null,
    ): JsonObject {
        fun text(name: String) = dir.resolve(name).let { if (Files.exists(it)) Files.readString(it) else "" }

        fun json(name: String): JsonObject? = dir.resolve(name).let { if (Files.exists(it)) Json.parseToJsonElement(Files.readString(it)) as? JsonObject else null }

        fun JsonObject?.field(name: String): JsonElement = this?.get(name) ?: JsonNull

        fun JsonElement.long(): Long? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

        fun JsonElement.word(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
        val targetLog = text("target.log")
        val collectorLog = text("collector.log")
        val batches = Regex("(?:tier-e-shared-openat-oracle|user-notif-oracle)-batch: phase=measured[^\\n]*operations=(\\d+)[^\\n]*workloadNs=(\\d+)")
            .findAll(targetLog)
            .toList()
        val target = Regex("split-benchmark-target: mode=\\S+ elapsedNs=(\\d+) cpuNs=(\\d+) peakRssKb=(-?\\d+)").find(targetLog)
        val user = Regex(
            "user-notif-oracle: [^\\n]*expected_operations=(\\d+) matched_operations=(\\d+) duplicate_operations=(\\d+) wrong_operations=(\\d+) unclassified_operations=(\\d+)",
        ).find(targetLog)
        val terminal = json("terminal-report.json")
        val collector = json("collector-process-metrics.json")
        val verdict = json("operation-verdict.json")
        val observations = json("benchmark-operations.json")
        val oracle = json("benchmark-oracle.json")
        val instrumented = Regex("(?m)^instrumented=1$").containsMatchIn(text("instrumented.env"))
        val expected = expectedOperations?.toLong() ?: oracle.field("expected_operations").long()

        fun logFields(
            log: String,
            marker: String,
            fields: List<String>,
        ): JsonObject? {
            val line = log.lineSequence().firstOrNull { marker in it } ?: return null
            return buildJsonObject {
                for (name in fields) {
                    val value = Regex("${Regex.escape(name)}=(\\d+)")
                        .find(line)
                        ?.groupValues
                        ?.get(1)
                        ?.toLongOrNull()
                    put(name, value?.let(::JsonPrimitive) ?: JsonNull)
                }
            }
        }
        val native = logFields(
            targetLog,
            "tier-e-benchmark:",
            listOf(
                "serialization_ns",
                "publication_bytes",
                "publication_latency_ns",
                "publication_cpu_ns",
            "peak_retained_bytes",
                "native_retained_bytes",
                "native_retention_exhaustions",
                "publication_count",
                "coalesced_publications",
                "failed_publications",
            ),
        )
        val receipt = logFields(
            collectorLog,
            "tier-e-collector-metrics:",
            listOf(
                "polls",
                "active_polls",
                "idle_polls",
                "receipt_samples",
            "receipt_min_ns",
                "receipt_mean_ns",
                "receipt_max_ns",
                "receipt_p50_upper_ns",
                "receipt_p95_upper_ns",
                "receipt_p99_upper_ns",
                "receipt_p99_fine_upper_ns",
            ),
        )
        val measured = batches.sumOf { it.groupValues[1].toLong() }
        val representative = observations.field("representative_only") == JsonPrimitive(true)
        val coverage = verdict.field("operation_coverage_verdict").word()
        val event = verdict.field("event_history_verdict").word()
        val recovered = verdict.field("recovered_operation_ids").long()
        val unresolved = observations.field("unresolved_observations").long()
        val tierE = mode.startsWith("dictionary") || mode.startsWith("inline")
        val userOracle = user?.let { match ->
            buildJsonObject {
                for ((index, name) in listOf("expected", "matched", "duplicates", "wrong", "unclassified").withIndex()) {
                    put(name, match.groupValues[index + 1].toLong())
                }
            }
        }
        val reason = when {
            status != 0 -> "child_failed"
            Files.exists(dir.resolve("timeout.env")) -> "timeout"
            batches.size != expectedBatches -> "missing_batches"
            expected != null && measured != expected -> "missing_operations"
            target == null -> "missing_target_metrics"
            tierE && terminal.field("evidence_integrity").word() != "COMPLETE" -> "terminal_incomplete"
            tierE && (collector.field("cpu_ns") == JsonNull || collector.field("peak_rss_kb") == JsonNull) -> "missing_collector_metrics"
            tierE && instrumented && native == null -> "missing_native_phases"
            tierE && instrumented && (receipt?.get("receipt_samples")?.long() ?: 0) <= 0 -> "missing_collector_receipt"
            tierE && mode.replace(Regex("-(v1|v2|hybrid)$"), "").endsWith("full-stream") && coverage != "PASS" -> "operation_verdict_incomplete"
            tierE && representative && (event != "NOT_APPLICABLE" || unresolved != 0L || (recovered ?: 0) <= 0) -> "representative_verdict_incomplete"
            tierE && !representative && !mode.replace(Regex("-(v1|v2|hybrid)$"), "").endsWith("full-stream") && coverage != "PASS" -> "operation_verdict_incomplete"
            mode.startsWith("user-notif") &&
                (
                    userOracle == null ||
                    userOracle["expected"] != userOracle["matched"] ||
                listOf("wrong", "duplicates", "unclassified").any { userOracle[it]?.long() != 0L }
                ) -> "user_notif_mismatch"
            else -> "complete"
        }

        fun num(value: String?): JsonElement = value?.toLongOrNull()?.let(::JsonPrimitive) ?: JsonNull
        return buildJsonObject {
            put("fork", fork)
            put("mode", mode)
            put("status", status)
            put("instrumented", instrumented)
            put("measured_batch_count", batches.size)
            put("measured_operations", measured)
            put("expected_operations", expected?.let(::JsonPrimitive) ?: JsonNull)
            put("workload_ns", JsonArray(batches.map { JsonPrimitive(it.groupValues[2].toLong()) }))
            put("total_elapsed_ns", num(target?.groupValues?.get(1)))
            put("target_cpu_ns", num(target?.groupValues?.get(2)))
            put("target_peak_rss_kb", num(target?.groupValues?.get(3)))
            put("collector_cpu_ns", collector.field("cpu_ns"))
            put("collector_peak_rss_kb", collector.field("peak_rss_kb"))
            put("collector_metrics_available", collector != null)
            put("native_phases", native ?: JsonNull)
            put("native_phases_available", native != null)
            put("collector_receipt", receipt ?: JsonNull)
            put("collector_receipt_available", receipt != null && (receipt["receipt_samples"]?.long() ?: 0) > 0)
            put("user_notif_oracle", userOracle ?: JsonNull)
            put("terminal_integrity", terminal.field("evidence_integrity"))
            put("operation_coverage_verdict", verdict.field("operation_coverage_verdict"))
            put("event_history_verdict", verdict.field("event_history_verdict"))
            put("representative_only", representative)
            put("unresolved_observations", observations.field("unresolved_observations"))
            put("recovered_operation_ids", verdict.field("recovered_operation_ids"))
            put("timed_out", Files.exists(dir.resolve("timeout.env")))
            put("policy_eligibility", false)
            put("fork_complete", reason == "complete")
            put("incomplete_reason", reason)
        }
    }
}
