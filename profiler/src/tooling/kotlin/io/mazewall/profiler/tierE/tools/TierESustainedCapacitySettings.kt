package io.mazewall.profiler.tierE.tools

/** Pilot/decision schedule and retention projection; each stage gets its own fresh trusted run. */
public data class TierESustainedCapacitySettings(
    val pilotRates: List<Int>,
    val runLevel: String,
    val load: String,
    val receiptClock: String,
    val stageSeconds: Int,
    val iterations: Int,
    val warmups: Int,
    val maxRetainedRecordsOverride: Long,
    val recordsPerOperationUpperBound: Int,
    val retainedBudgetBytes: Long,
    val runDecision: Boolean,
    val decisionForks: Int,
    val decisionStageSeconds: Int,
    val shadowOnFailure: Boolean,
    val runNodeNoiseProbe: Boolean,
) {
    public data class Stage(
        val rate: Int,
        val seconds: Int,
        val forks: Int,
        val batches: Int,
        val maxRetainedRecords: Long,
        val label: String,
    )

    public fun plan(
        rate: Int,
        seconds: Int,
        forks: Int,
        label: String,
    ): Stage {
        require(rate > 0 && seconds > 0 && forks > 0)
        val operations = Math.multiplyExact(rate.toLong(), seconds.toLong())
        require(operations % iterations == 0L && operations / iterations <= Int.MAX_VALUE) {
            "Sustained stage operations must be divisible by iterations"
        }
        val limit = if (maxRetainedRecordsOverride > 0) {
            maxRetainedRecordsOverride
        } else {
            Math.multiplyExact(
                Math.addExact(operations, Math.multiplyExact(warmups.toLong(), iterations.toLong())),
                recordsPerOperationUpperBound.toLong(),
            )
        }
        return Stage(rate, seconds, forks, (operations / iterations).toInt(), limit, label)
    }

    public companion object {
        public fun load(environment: Map<String, String> = System.getenv()): TierESustainedCapacitySettings {
            fun value(
                name: String,
                default: String,
            ) = environment["MAZEWALL_TIER_E_SUSTAINED_$name"] ?: default

            fun number(
                name: String,
                default: Long,
                minimum: Long = 1,
            ): Long =
                value(name, default.toString())
                    .toLongOrNull()
                ?.takeIf { it >= minimum } ?: throw IllegalArgumentException("Invalid sustained $name")

            fun flag(
                name: String,
                default: Boolean,
            ): Boolean =
                when (value(name, default.toString())) {
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("Invalid sustained $name")
            }
            val rates = value("PILOT_RATES", "1000 2000 4000 8000 16000 32000 64000")
                .trim()
                .split(Regex("\\s+"))
                .map { rate ->
                    rate.toIntOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("Invalid sustained pilot rate: $rate")
                }
            require(rates.isNotEmpty() && rates.size == rates.toSet().size && rates == rates.sorted()) { "Pilot rates must increase" }
            val level = value("RUN_LEVEL", "smoke")
            require(level in setOf("smoke", "development", "decision", "custom")) { "Invalid sustained run level" }
            val load = value("LOAD", "below_capacity")
            require(load in setOf("below_capacity", "near_capacity", "intentional_overload"))
            val receipt = value("RECEIPT_CLOCK", "monotonic")
            require(receipt in setOf("monotonic", "disabled"))
            val iterations = number("ITERATIONS", 200).toInt()
            val warmups = (environment["MAZEWALL_TIER_E_WARMUPS"] ?: "3")
                .toIntOrNull()
                ?.takeIf { it >= 0 } ?: throw IllegalArgumentException("Invalid Tier E warmups")
            val settings = TierESustainedCapacitySettings(
                rates,
                level,
                load,
                receipt,
                number("STAGE_SECONDS", 10).toInt(),
                iterations,
                warmups,
                number("MAX_RETAINED_RECORDS", 0, 0),
                number("RECORDS_PER_OPERATION_UPPER_BOUND", 3).toInt(),
                number("RETAINED_BUDGET_BYTES", 67_108_864),
                flag("RUN_DECISION", false),
                number("DECISION_FORKS", 5).toInt(),
                number("DECISION_STAGE_SECONDS", 60).toInt(),
                flag("SHADOW_ON_FAILURE", true),
                flag("RUN_NODE_NOISE_PROBE", false),
            )
            require(settings.retainedBudgetBytes <= Long.MAX_VALUE / 3) { "Retained budget overflow" }
            return settings
        }
    }
}
