package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Version-two opt-in: pin the actual Docker image ID, not a mutable tag or absent label. */
public object TierERootfulPinnedImageProfile {
    public data class Pinned(
        val profile: TierERootfulGateProfile,
        val imageId: String,
    )

    public fun load(file: Path): Pinned {
        require(Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) <= 65_536L) {
            "Pinned rootful profile is missing or oversized"
        }
        val text = Files.readString(file)
        TierEProfileJson.requireUniqueKeys(text)
        val document = Json.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("Pinned rootful profile must be an object")
        require(document.keys == setOf("schema_version", "container", "cpu_set", "image_id")) {
            "Pinned rootful profile fields differ"
        }
        val version = document["schema_version"] as? JsonPrimitive
        require(version?.takeUnless(JsonPrimitive::isString)?.intOrNull == 2) { "Pinned rootful profile requires schema version 2" }
        val container = document["container"] as? JsonObject ?: throw IllegalArgumentException("Missing pinned gate container")
        require(container.keys == setOf("engine", "image") && container.getValue("engine").jsonPrimitive.content == "docker") {
            "Pinned rootful profile requires explicit Docker image"
        }
        val image = container.getValue("image").jsonPrimitive
        require(image.isString && image.content.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:/-]+"))) { "Invalid pinned image tag" }
        val selected = document.getValue("cpu_set").jsonPrimitive
        require(selected.isString) { "Pinned rootful CPU set must be a string" }
        val cpus = selected.content.split(',')
        require(
            cpus.size == 2 &&
            cpus.distinct().size == 2 &&
            cpus.all { cpu -> cpu.toIntOrNull()?.let { it >= 0 && it.toString() == cpu } == true },
        ) { "Pinned rootful gate requires two distinct selected CPUs" }
        val id = document.getValue("image_id").jsonPrimitive
        require(id.isString && id.content.matches(Regex("sha256:[a-f0-9]{64}"))) { "Pinned rootful gate requires an exact SHA-256 image ID" }
        return Pinned(TierERootfulGateProfile(image.content, cpus.joinToString(",")), id.content)
    }
}
