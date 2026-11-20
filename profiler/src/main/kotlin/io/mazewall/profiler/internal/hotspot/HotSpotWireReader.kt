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
import java.util.Locale

private const val MAX_SESSION_ID_LENGTH = 128
private const val MAX_EVENT_ID_LENGTH = 256
private const val MAX_JSON_DEPTH = 64
private const val OBSERVED_SCHEMA = 3uL

internal data class HotSpotWireRecord(
    val kind: String,
    val sessionId: String,
    val eventId: String?,
    val objectValue: JsonObject,
    val rawJson: String,
    val byteSize: Int,
)

internal data class HotSpotWireReceipt(
    val records: ULong,
    val bytes: ULong,
    val sha256: String,
    val validEof: Boolean,
)

internal class HotSpotWireReader(
    private val limits: HotSpotReplayLimits,
    private val expectedSchemaVersion: ULong = 1uL,
) {
    init {
        require(expectedSchemaVersion in 1uL..OBSERVED_SCHEMA)
    }

    fun read(
        input: InputStream,
        consume: (HotSpotWireRecord) -> Unit,
    ): HotSpotWireReceipt {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var prefixRecords = 0uL
        var prefixBytes = 0uL
        var summarySeen = false
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
                val shaHex = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
                return HotSpotWireReceipt(prefixRecords, prefixBytes, shaHex, validEof = summarySeen)
            }
            var start = 0
            for (index in 0 until count) {
                if (buffer[index] == '\n'.code.toByte()) {
                    append(line, buffer, start, index - start)
                    checkHotSpotProtocol(line.size() != 0, "empty JSONL record")
                    checkHotSpotProtocol(++records <= limits.maxWireRecords, "wire record limit exceeded")
                    checkHotSpotProtocol(!summarySeen, "appended record after final summary")
                    val rawLineBytes = line.toByteArray()
                    val decoded = decode(rawLineBytes, decoder)
                    if (decoded.kind == "session_summary") {
                        summarySeen = true
                    } else {
                        prefixRecords++
                        prefixBytes += rawLineBytes.size.toULong() + 1uL
                        digest.update(rawLineBytes)
                        digest.update('\n'.code.toByte())
                    }
                    consume(decoded)
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
        when (expectedSchemaVersion) {
            OBSERVED_SCHEMA -> checkHotSpotProtocol(!row.boolean("policy_eligibility"), "observations cannot be policy eligible")
            2uL -> checkHotSpotProtocol(!row.containsKey("policy_eligibility"), "schema2 cannot carry observation policy fields")
        }
        val session = row.text("session_id")
        checkHotSpotProtocol(session.length in 1..MAX_SESSION_ID_LENGTH, "invalid session identity length")
        val eventId = row.optionalText("event_id")
        checkHotSpotProtocol(eventId == null || (eventId.length <= MAX_EVENT_ID_LENGTH && eventId.startsWith("$session:")), "occurrence/session mismatch")
        val kind = row.text("kind")
        if (expectedSchemaVersion != 1uL) {
            val payload = when (kind) {
                "session_started", "collector_ready", "collector_error" -> row["lifecycle"] as? JsonObject
                "enrichment" -> row["evidence"] as? JsonObject
                else -> null
            }
            val provider = payload?.get("provider") as? JsonObject
            val canonical = listOf("name", "gadget_revision", "profiler_revision", "patch_sha256")
            checkHotSpotProtocol(
                provider == null || provider.keys.none { key -> canonical.any { it != key && it.equals(key, ignoreCase = true) } },
                "noncanonical provider key",
            )
        }
        return HotSpotWireRecord(kind, session, eventId, row, text, bytes.size)
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
