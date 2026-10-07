package io.mazewall.enforcer.internal.artifact

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** Artifacts accept canonical JSON token spelling with optional formatting whitespace. */
internal object StrictArtifactJson {
    private val json = Json { isLenient = false }

    fun parse(document: String): JsonObject {
        val value = json.parseToJsonElement(document)
        require(withoutFormatting(document) == value.toString()) { "Artifact JSON has duplicate fields or noncanonical token spelling" }
        return value as? JsonObject ?: throw IllegalArgumentException("Artifact root must be an object")
    }

    private fun withoutFormatting(text: String): String =
        buildString {
        var quoted = false
        var escaped = false
        for (char in text) {
            if (quoted || !char.isWhitespace()) append(char)
            when {
                escaped -> escaped = false
                quoted && char == '\\' -> escaped = true
                char == '"' -> quoted = !quoted
            }
        }
    }
}

internal fun JsonObject.fields(vararg names: String): JsonObject = also { require(keys == names.toSet()) { "Artifact fields differ from required schema: $keys" } }

internal fun JsonElement.objectValue(): JsonObject = this as? JsonObject ?: throw IllegalArgumentException("Expected artifact object")

internal fun JsonElement.arrayValue(): JsonArray = this as? JsonArray ?: throw IllegalArgumentException("Expected artifact array")

internal fun JsonElement.stringValue(): String {
    val value = this as? JsonPrimitive ?: throw IllegalArgumentException("Expected artifact string")
    require(value.isString) { "Expected artifact string" }
    return value.content
}

internal fun JsonElement.intValue(): Int {
    val value = this as? JsonPrimitive ?: throw IllegalArgumentException("Expected artifact integer")
    require(!value.isString) { "Expected artifact integer" }
    return value.intOrNull ?: throw IllegalArgumentException("Expected artifact integer")
}

internal fun JsonElement.booleanValue(): Boolean {
    val value = this as? JsonPrimitive ?: throw IllegalArgumentException("Expected artifact boolean")
    require(!value.isString) { "Expected artifact boolean" }
    return value.booleanOrNull ?: throw IllegalArgumentException("Expected artifact boolean")
}
