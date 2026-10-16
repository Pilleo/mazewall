package io.mazewall.profiler.internal.hotspot

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

private const val MAX_SESSION_ID_LENGTH = 128
private const val MAX_EVENT_ID_LENGTH = 256
private const val MAX_JSON_DEPTH = 64

internal data class HotSpotWireRecord(
    val kind: String,
    val sessionId: String,
    val eventId: String?,
    val objectValue: JsonObject,
    val rawJson: String,
    val byteSize: Int,
)

internal class HotSpotWireReader(
    private val limits: HotSpotReplayLimits,
    private val expectedSchemaVersion: ULong = 1uL,
) {
    init {
        require(expectedSchemaVersion == 1uL || expectedSchemaVersion == 2uL)
    }

    fun read(
        input: InputStream,
        consume: (HotSpotWireRecord) -> Unit,
    ) {
        val line = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val decoder = Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        var records = 0
        while (true) {
            val count = input.read(buffer)
            if (count == -1) {
                checkHotSpotProtocol(line.size() == 0, "truncated JSONL record without newline")
                return
            }
            var start = 0
            for (index in 0 until count) {
                if (buffer[index] == '\n'.code.toByte()) {
                    append(line, buffer, start, index - start)
                    checkHotSpotProtocol(line.size() != 0, "empty JSONL record")
                    checkHotSpotProtocol(++records <= limits.maxWireRecords, "wire record limit exceeded")
                    consume(decode(line.toByteArray(), decoder))
                    line.reset()
                    start = index + 1
                }
            }
            append(line, buffer, start, count - start)
        }
    }

    private fun append(
        line: ByteArrayOutputStream,
        buffer: ByteArray,
        start: Int,
        count: Int,
    ) {
        checkHotSpotProtocol(count <= limits.maxLineBytes - line.size(), "wire line byte limit exceeded")
        line.write(buffer, start, count)
    }

    private fun decode(
        bytes: ByteArray,
        decoder: CharsetDecoder,
    ): HotSpotWireRecord {
        val text = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (failure: java.nio.charset.CharacterCodingException) {
            throw HotSpotProtocolException("invalid UTF-8 record", failure)
        }
        checkDepth(text)
        val row = parseObject(text)
        checkHotSpotProtocol(row.ulong("schema_version") == expectedSchemaVersion, "unsupported collector schema")
        val session = row.text("session_id")
        checkHotSpotProtocol(session.length in 1..MAX_SESSION_ID_LENGTH, "invalid session identity length")
        val eventId = row.optionalText("event_id")
        checkHotSpotProtocol(eventId == null || (eventId.length <= MAX_EVENT_ID_LENGTH && eventId.startsWith("$session:")), "occurrence/session mismatch")
        return HotSpotWireRecord(row.text("kind"), session, eventId, row, text, bytes.size)
    }

    private fun parseObject(text: String): JsonObject =
        try {
        Json.parseToJsonElement(text) as? JsonObject ?: throw HotSpotProtocolException("record is not an object")
    } catch (failure: SerializationException) {
        throw HotSpotProtocolException("invalid JSON record", failure)
    }

    private fun checkDepth(text: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (character in text) {
            when {
                escaped -> escaped = false
                quoted && character == '\\' -> escaped = true
                character == '"' -> quoted = !quoted
                !quoted && (character == '{' || character == '[') -> {
                    depth++
                    checkHotSpotProtocol(depth <= MAX_JSON_DEPTH, "JSON nesting limit exceeded")
                }
                !quoted && (character == '}' || character == ']') -> depth--
            }
        }
    }
}

internal fun JsonObject.objectField(key: String): JsonObject = this[key] as? JsonObject ?: throw HotSpotProtocolException("missing object $key")

internal fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw HotSpotProtocolException("missing string $key")

internal fun JsonObject.optionalText(key: String): String? = if (containsKey(key)) text(key) else null

internal fun JsonObject.ulong(key: String): ULong = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toULongOrNull() ?: throw HotSpotProtocolException("invalid uint64 $key")

internal fun JsonObject.optionalUlong(key: String): ULong? = if (containsKey(key)) ulong(key) else null

internal fun JsonObject.uint(key: String): UInt = ulong(key).takeIf { it <= UInt.MAX_VALUE.toULong() }?.toUInt() ?: throw HotSpotProtocolException("invalid uint32 $key")

internal fun JsonObject.optionalUint(key: String): UInt? = if (containsKey(key)) uint(key) else null

internal fun JsonObject.boolean(key: String): Boolean = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: throw HotSpotProtocolException("invalid Boolean $key")

internal fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: throw HotSpotProtocolException("missing array $key")
