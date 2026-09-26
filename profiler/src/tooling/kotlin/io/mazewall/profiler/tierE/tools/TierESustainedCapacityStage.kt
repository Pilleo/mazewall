package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.nio.file.Path

/** Explicit capacity eligibility; diagnostic coverage never upgrades an ineligible performance fork. */
public object TierESustainedCapacityStage {
    public fun eligible(
        results: Path,
        retainedBudgetBytes: Long,
    ): Boolean {
        require(retainedBudgetBytes > 0 && retainedBudgetBytes <= Long.MAX_VALUE / 3) { "Invalid retained budget" }
        val limit = Math.multiplyExact(retainedBudgetBytes, 3) / 4
        val rows = Files.readAllLines(results).filter(String::isNotBlank).map { line ->
            Json.parseToJsonElement(line) as? JsonObject ?: throw IllegalArgumentException("Invalid capacity JSONL row")
        }
        val terminals = rows.filter { it["kind"] == JsonPrimitive("terminal") }
        val forks = rows.filter { it["kind"] == JsonPrimitive("fork-output") && it["sustained_collector"] is JsonObject }
        return terminals.size == 1 &&
            terminals.single()["success"] == JsonPrimitive(true) &&
            forks.isNotEmpty() &&
            forks.all { row ->
                val evidence = row["sustained_collector"] as JsonObject
                evidence["evidence_integrity"] == JsonPrimitive("COMPLETE") &&
                    evidence["performance_eligible"] == JsonPrimitive(true) &&
                    (evidence["retained_state_high_water_bytes"] as? JsonPrimitive)?.longOrNull?.let { it <= limit } == true
            }
    }
}
