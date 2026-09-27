package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/** Strict operator-selected placement and image; local Docker endpoint is resolved separately. */
public data class TierERootfulGateProfile(
    val image: String,
    val cpuSet: String,
) {
    public companion object {
        public fun load(file: Path): TierERootfulGateProfile {
            val document = Json.parseToJsonElement(Files.readString(file)) as? JsonObject
                ?: throw IllegalArgumentException("Rootful gate profile must be an object")
            require(document.keys == setOf("schema_version", "container", "cpu_set")) { "Unknown rootful gate profile fields" }
            require(document["schema_version"]?.jsonPrimitive?.content == "1") { "Unsupported rootful gate profile version" }
            val container = document["container"] as? JsonObject ?: throw IllegalArgumentException("Missing gate container")
            require(container.keys == setOf("engine", "image")) { "Unknown gate container fields" }
            require(container.getValue("engine").jsonPrimitive.content == "docker") { "Rootful gate requires explicit Docker" }
            val image = container.getValue("image").jsonPrimitive.content
            require(image.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:/-]+"))) { "Invalid rootful image" }
            val cpus = document
                .getValue("cpu_set")
                .jsonPrimitive.content
                .split(',')
            require(cpus.size == 2 && cpus.all { it.toIntOrNull()?.let { value -> value >= 0 && value.toString() == it } == true } && cpus.distinct().size == 2) {
                "Rootful gate requires two distinct nonnegative CPU IDs"
            }
            return TierERootfulGateProfile(image, cpus.joinToString(","))
        }
    }
}
