package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Normalize only process-local loader and hidden-class identities, preserving every method and BCI. */
public object TierECoverageStackIdentity {
    public fun normalize(stacks: JsonObject): JsonObject {
        val loaders = mutableMapOf<String, String>()
        val hidden = mutableMapOf<String, String>()
        val prefixCounts = mutableMapOf<String, Int>()
        val hiddenName = Regex("^(.*)[./]0x[0-9a-fA-F]+$")
        return buildJsonObject {
            for (key in stacks.keys.sortedBy { it.toIntOrNull() ?: throw IllegalArgumentException("Invalid stack path: $it") }) {
                val records = stacks[key] as? JsonArray ?: throw IllegalArgumentException("Malformed coverage stacks")
                val normalized = records
                    .map { record ->
                    val fields = record as? JsonArray ?: throw IllegalArgumentException("Malformed full-stack record")
                    require(fields.size >= 3 && (fields.size - 3) % 6 == 0) { "Malformed full-stack record" }
                    val output = fields.toMutableList()
                    for (offset in 3 until fields.size step 6) {
                        val loader = fields[offset].jsonPrimitive.content
                        if (Regex("^loader-\\d+$").matches(loader)) {
                            output[offset] = JsonPrimitive(loaders.getOrPut(loader) { "loader-local-${loaders.size}" })
                        }
                        val name = fields[offset + 1].jsonPrimitive.content
                        val match = hiddenName.matchEntire(name)
                        if (match != null) {
                            val identity = "$loader:$name"
                            output[offset + 1] = JsonPrimitive(
                                hidden.getOrPut(identity) {
                                val prefix = "${output[offset].jsonPrimitive.content}:${match.groupValues[1]}"
                                val ordinal = prefixCounts.getOrDefault(prefix, 0)
                                prefixCounts[prefix] = ordinal + 1
                                "${match.groupValues[1]}#hidden-local-$ordinal"
                            },
                            )
                        }
                    }
                    JsonArray(output)
                }.sortedBy { it.toString() }
                put(key, JsonArray(normalized))
            }
        }
    }
}
