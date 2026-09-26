package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One bounded /proc syscall-loop observation and external process-time receipt. */
public data class TierENodeNoiseSample(
    val iterations: Long,
    val userSeconds: Double,
    val systemSeconds: Double,
    val elapsedSeconds: Double,
) {
    public fun json(): JsonObject =
        buildJsonObject {
        put("iterations", iterations)
        put("user_seconds", userSeconds)
        put("system_seconds", systemSeconds)
        put("elapsed_seconds", elapsedSeconds)
    }

    public companion object {
        public fun parse(output: String): TierENodeNoiseSample {
            fun number(field: String): Double =
                Regex("(?:^|\\s)$field=([0-9]+(?:\\.[0-9]+)?)", RegexOption.MULTILINE)
                .find(output)
                    ?.groupValues
                    ?.get(1)
                    ?.toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it >= 0 }
                ?: throw IllegalArgumentException("Missing Tier E node-noise $field")
            val iterations = number("syscall_loop_iterations")
            require(iterations > 0 && iterations <= Long.MAX_VALUE && iterations == iterations.toLong().toDouble()) {
                "Invalid node-noise loop count"
            }
            return TierENodeNoiseSample(iterations.toLong(), number("user_seconds"), number("system_seconds"), number("elapsed_seconds"))
        }
    }
}
