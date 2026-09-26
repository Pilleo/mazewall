package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A representative unique-edge record is not an exact per-operation event stream. */
public object TierEBenchmarkOperationAcceptance {
    public fun accept(
        emissionMode: String,
        observations: JsonObject,
        verdict: JsonObject,
    ): Boolean {
        val coverage = verdict["operation_coverage_verdict"]?.toString()
        val history = verdict["event_history_verdict"]?.toString()
        if (verdict["terminal_evidence_integrity"] != JsonPrimitive("COMPLETE") ||
            verdict["policy_eligibility"] != JsonPrimitive(false) ||
            verdict["unresolved_observations"] != JsonPrimitive(0) ||
            observations["unresolved_observations"] != JsonPrimitive(0)
        ) {
            return false
        }
        return when (emissionMode) {
            "full-stream" -> observations["representative_only"] == JsonPrimitive(false) &&
                coverage == "\"PASS\"" &&
                history == "\"PASS\""
            "unique-stack-syscall" -> observations["representative_only"] == JsonPrimitive(true) &&
                coverage in setOf("\"PASS\"", "\"INCOMPLETE\"") &&
                history == "\"NOT_APPLICABLE\"" &&
                (verdict["recovered_operation_ids"] as? JsonPrimitive)?.longOrNull?.let { it > 0 } == true
            else -> false
        }
    }
}
