package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/** Decide only from artifacts observed at this arm's terminal boundary. */
public object TierELongMatchedArmStatus {
    private fun read(
        dir: Path,
        name: String,
    ): JsonObject? =
        try {
        Json.parseToJsonElement(Files.readString(dir.resolve(name))) as? JsonObject
    } catch (_: Exception) {
        null
    }

    public fun complete(
        dir: Path,
        baseline: Boolean,
    ): Boolean {
        if (baseline) {
            val summary = read(dir, "summary.json") ?: return false
            val target = read(dir, "target-cpu.json") ?: return false
            return summary["mode"] == JsonPrimitive("baseline") &&
                summary["policy_eligibility"] == JsonPrimitive(false) &&
                (target["target_cpu_ns"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.let { it > 0 } == true &&
                !Files.exists(dir.resolve("collector-process-metrics.json"))
        }
        val coverage = read(dir, "coverage-verdict.json") ?: return false
        val terminal = read(dir, "terminal-report.json") ?: return false
        Files.writeString(
            dir.resolve("summary.json"),
            buildJsonObject {
            put("coverage", coverage)
            put("terminal", terminal)
            put("policy_eligibility", false)
        }.toString() +
            "\n",
        )
        return coverage["coverage_verdict"] == JsonPrimitive("PASS") &&
            (coverage["recovered_edges"] as? JsonPrimitive)?.longOrNull == 72L &&
            coverage["policy_eligibility"] == JsonPrimitive(false) &&
            terminal["evidence_integrity"] == JsonPrimitive("COMPLETE") &&
            terminal["policy_eligibility"] == JsonPrimitive(false)
    }

    public fun legacySummary(
        dir: Path,
        allowIncomplete: Boolean,
    ): Boolean {
        val coverage = read(dir, "coverage-verdict.json") ?: return false
        val terminal = read(dir, "terminal-report.json") ?: return false
        Files.writeString(
            dir.resolve("summary.json"),
            buildJsonObject {
            put("coverage", coverage)
            put("terminal", terminal)
            put("policy_eligibility", false)
        }.toString() +
            "\n",
        )
        val complete = coverage["coverage_verdict"] == JsonPrimitive("PASS") &&
            terminal["evidence_integrity"] == JsonPrimitive("COMPLETE")
        val bounded = coverage["coverage_verdict"] == JsonPrimitive("INCOMPLETE") &&
            terminal["evidence_integrity"] == JsonPrimitive("INCOMPLETE")
        return (complete || allowIncomplete && bounded) && terminal["policy_eligibility"] == JsonPrimitive(false)
    }
}
