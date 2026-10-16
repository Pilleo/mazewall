package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/** Reject repeated decoded keys before JsonObject can collapse an operator's two values. */
internal object TierEProfileJson {
    private const val MAX_DEPTH = 64

    fun requireUniqueKeys(text: String) {
        // Limit nesting before the JSON decoder runs; afterward the decoder
        // validates primitive values and complete syntax we do not duplicate here.
        var index = 0

        fun whitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun consume(expected: Char) {
            whitespace()
            require(index < text.length && text[index++] == expected) { "Malformed Tier E profile JSON" }
        }

        fun stringToken(): String {
            whitespace()
            require(index < text.length && text[index] == '"') { "Malformed Tier E profile key" }
            val start = index++
            var escaped = false
            while (index < text.length) {
                val character = text[index++]
                if (escaped) {
                    escaped = false
                } else if (character == '\\') {
                    escaped = true
                } else if (character == '"') {
                    return Json.parseToJsonElement(text.substring(start, index)).jsonPrimitive.content
                }
            }
            throw IllegalArgumentException("Unterminated Tier E profile string")
        }

        fun value(depth: Int) {
            require(depth <= MAX_DEPTH) { "Tier E profile JSON nesting exceeds bound" }
            whitespace()
            require(index < text.length) { "Malformed Tier E profile JSON value" }
            when (text[index]) {
                '{' -> {
                    index++
                    whitespace()
                    val names = mutableSetOf<String>()
                    if (index < text.length && text[index] == '}') {
                        index++
                    } else {
                        while (true) {
                            val name = stringToken()
                            require(names.add(name)) { "Duplicate Tier E profile key: $name" }
                            consume(':')
                            value(depth + 1)
                            whitespace()
                            require(index < text.length) { "Malformed Tier E profile object" }
                            val separator = text[index++]
                            if (separator == '}') break
                            require(separator == ',') { "Malformed Tier E profile object separator" }
                        }
                    }
                }
                '[' -> {
                    index++
                    whitespace()
                    if (index < text.length && text[index] == ']') {
                        index++
                    } else {
                        while (true) {
                            value(depth + 1)
                            whitespace()
                            require(index < text.length) { "Malformed Tier E profile array" }
                            val separator = text[index++]
                            if (separator == ']') break
                            require(separator == ',') { "Malformed Tier E profile array separator" }
                        }
                    }
                }
                '"' -> stringToken()
                else -> {
                    while (index < text.length && !text[index].isWhitespace() && text[index] !in ",]}") index++
                }
            }
        }

        value(0)
        whitespace()
        require(index == text.length) { "Unexpected trailing Tier E profile JSON" }
        Json.parseToJsonElement(text)
    }
}
