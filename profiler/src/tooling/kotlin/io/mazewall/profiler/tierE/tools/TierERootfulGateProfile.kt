package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
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
            require(Files.size(file) <= 65_536L) { "Tier E rootful profile exceeds size bound" }
            val text = Files.readString(file)
            TierEProfileJson.requireUniqueKeys(text)
            val document = Json.parseToJsonElement(text) as? JsonObject
                ?: throw IllegalArgumentException("Rootful gate profile must be an object")
            require(document.keys == setOf("schema_version", "container", "cpu_set")) { "Unknown rootful gate profile fields" }
            val version = document["schema_version"] as? JsonPrimitive
            require(version?.takeUnless(JsonPrimitive::isString)?.intOrNull == 1) { "Unsupported rootful gate profile version" }
            val container = document["container"] as? JsonObject ?: throw IllegalArgumentException("Missing gate container")
            require(container.keys == setOf("engine", "image")) { "Unknown gate container fields" }
            val engine = container.getValue("engine").jsonPrimitive
            require(engine.isString && engine.content == "docker") { "Rootful gate requires explicit Docker" }
            val name = container.getValue("image").jsonPrimitive
            require(name.isString) { "Rootful gate image must be a JSON string" }
            val image = name.content
            require(image.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:/-]+"))) { "Invalid rootful image" }
            val selection = document.getValue("cpu_set").jsonPrimitive
            require(selection.isString) { "Rootful CPU set must be a JSON string" }
            val cpus = selection.content.split(',')
            require(cpus.size == 2 && cpus.all { it.toIntOrNull()?.let { value -> value >= 0 && value.toString() == it } == true } && cpus.distinct().size == 2) {
                "Rootful gate requires two distinct nonnegative CPU IDs"
            }
            return TierERootfulGateProfile(image, cpus.joinToString(","))
        }
    }
}
