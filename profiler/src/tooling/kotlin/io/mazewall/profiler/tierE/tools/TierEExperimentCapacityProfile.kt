package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Typed capacity schedule adapter; no ambient experiment environment enters a selected profile. */
internal object TierEExperimentCapacityProfile {
    private val fields = mapOf(
        "pilot_rates" to "PILOT_RATES",
        "run_level" to "RUN_LEVEL",
        "load" to "LOAD",
        "receipt_clock" to "RECEIPT_CLOCK",
        "stage_seconds" to "STAGE_SECONDS",
        "iterations" to "ITERATIONS",
        "max_retained_records" to "MAX_RETAINED_RECORDS",
        "records_per_operation_upper_bound" to "RECORDS_PER_OPERATION_UPPER_BOUND",
        "retained_budget_bytes" to "RETAINED_BUDGET_BYTES",
        "run_decision" to "RUN_DECISION",
        "decision_forks" to "DECISION_FORKS",
        "decision_stage_seconds" to "DECISION_STAGE_SECONDS",
        "shadow_on_failure" to "SHADOW_ON_FAILURE",
        "run_node_noise_probe" to "RUN_NODE_NOISE_PROBE",
    )

    fun load(
        section: JsonObject,
        base: TierETrustworthySettings,
    ): TierESustainedCapacitySettings {
        require(section.keys.all { it in fields }) { "Unknown capacity setting: ${section.keys - fields.keys}" }
        val values = section
            .map { (key, value) ->
            require(value is JsonPrimitive && value.content != "null") { "Invalid capacity setting: $key" }
            "MAZEWALL_TIER_E_SUSTAINED_${fields.getValue(key)}" to value.content
        }.toMap()
        return TierESustainedCapacitySettings.load(
            mapOf(
                "MAZEWALL_TIER_E_SUSTAINED_ITERATIONS" to base.iterations.toString(),
                "MAZEWALL_TIER_E_SUSTAINED_RUN_LEVEL" to base.runLevel,
                "MAZEWALL_TIER_E_SUSTAINED_LOAD" to base.sustainedLoad,
                "MAZEWALL_TIER_E_SUSTAINED_RECEIPT_CLOCK" to base.receiptClock,
                "MAZEWALL_TIER_E_WARMUPS" to base.warmups.toString(),
            ) +
                values,
        )
    }
}
