package io.mazewall.profiler.internal.hotspot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val MAX_DOCUMENT_CHARS = 64 * 1024 * 1024
private const val MAX_DOCUMENT_DEPTH = 64
private const val MAX_SESSION_LENGTH = 128

/** Explicit diagnostic serialization preserves raw provider fields and independently bounded retention facts. */
internal object HotSpotEvidenceDocument {
    fun encode(value: StructuredProfileEvidence): String =
        buildJsonObject {
        put("formatVersion", 1)
        put("sessionId", value.sessionId)
        put("finalized", value.finalized)
        put("pendingOccurrences", JsonPrimitive(value.pendingOccurrences.toString().toBigInteger()))
        put("discardedOccurrences", JsonPrimitive(value.discardedOccurrences.toString().toBigInteger()))
        put("discardedRecoveries", JsonPrimitive(value.discardedRecoveries.toString().toBigInteger()))
        put("retainedBytes", value.retainedBytes)
        put("collectionLossComplete", false)
        put("recoveries", JsonArray(value.recoveries.map(::JsonPrimitive)))
        put(
            "records",
            JsonArray(
                value.records.map { record ->
            buildJsonObject {
            put("eventId", record.eventId)
            put("event", Json.parseToJsonElement(record.event.rawJson))
            put("evidence", Json.parseToJsonElement(record.evidence.rawJson))
        }
        },
            ),
        )
        put(
            "summary",
            buildJsonObject {
            put(
                "received",
                JsonPrimitive(
                    value.summary.received
                .toString()
                .toBigInteger(),
                ),
            )
            put(
                "resolved",
                JsonPrimitive(
                    value.summary.resolved
                .toString()
                .toBigInteger(),
                ),
            )
            put(
                "unavailable",
                JsonPrimitive(
                    value.summary.unavailable
                .toString()
                .toBigInteger(),
                ),
            )
            put(
                "skipped",
                JsonPrimitive(
                    value.summary.skipped
                .toString()
                .toBigInteger(),
                ),
            )
            put("terminalAccountingComplete", value.summary.terminalAccountingComplete)
            put("physicalDrainComplete", value.summary.physicalDrainComplete)
            value.summary.observedEventLoss?.let { put("observedEventLoss", JsonPrimitive(it.toString().toBigInteger())) }
            put("raw", Json.parseToJsonElement(value.summary.rawJson))
        },
        )
    }.toString().also { require(it.length <= MAX_DOCUMENT_CHARS) { "Structured evidence report exceeds size limit" } }

    fun decode(text: String): StructuredProfileEvidence {
        require(text.length <= MAX_DOCUMENT_CHARS) { "Structured evidence report exceeds size limit" }
        checkDepth(text)
        val root = Json.parseToJsonElement(text) as? JsonObject ?: throw HotSpotProtocolException("evidence report is not an object")
        checkHotSpotProtocol(root.ulong("formatVersion") == 1uL && !root.boolean("collectionLossComplete"), "unsupported evidence report completeness/version")
        val session = root.text("sessionId")
        checkHotSpotProtocol(session.length in 1..MAX_SESSION_LENGTH, "invalid report session identity")
        val records = root.array("records")
        val limits = HotSpotReplayLimits()
        checkHotSpotProtocol(records.size <= limits.maxRetainedOccurrences, "report occurrence limit exceeded")
        val mapper = HotSpotWireMapper(limits)
        val ids = mutableSetOf<String>()
        val mapped = records.map { element ->
            val item = element as? JsonObject ?: throw HotSpotProtocolException("report occurrence is not an object")
            val id = item.text("eventId")
            checkHotSpotProtocol(id.startsWith("$session:") && ids.add(id), "duplicate/foreign report occurrence")
            val packet = buildJsonObject {
                put("event", item.objectField("event"))
                put("evidence", item.objectField("evidence"))
            }
            val raw = packet.toString()
            checkHotSpotProtocol(raw.toByteArray().size <= limits.maxLineBytes * 2, "report occurrence exceeds byte limit")
            val row = HotSpotWireRecord("event", session, id, packet, raw, raw.length)
            HotSpotOccurrence(id, mapper.event(row), mapper.evidence(row))
        }
        val summary = root.objectField("summary")
        val final = HotSpotReplaySummary(
            summary.ulong("received"),
            summary.ulong("resolved"),
            summary.ulong("unavailable"),
            summary.ulong("skipped"),
            summary.boolean("terminalAccountingComplete"),
            summary.boolean("physicalDrainComplete"),
            summary.optionalUlong("observedEventLoss"),
            summary.objectField("raw").toString(),
        )
        val recoveries = root.array("recoveries")
        checkHotSpotProtocol(recoveries.size <= limits.maxTrackedOccurrences, "report recovery limit exceeded")
        val retained = root.uint("retainedBytes")
        checkHotSpotProtocol(retained <= limits.maxRetainedBytes.toUInt(), "report retention budget exceeded")
        return StructuredProfileEvidence(
            session,
            mapped,
            final,
            root.ulong("discardedOccurrences"),
            retained.toInt(),
            recoveries.map(::recoveryText),
            root.ulong("discardedRecoveries"),
            root.boolean("finalized"),
            root.ulong("pendingOccurrences"),
        )
    }

    private fun recoveryText(value: JsonElement): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw HotSpotProtocolException("invalid report recovery")

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
                    require(depth <= MAX_DOCUMENT_DEPTH) { "Evidence report nesting limit exceeded" }
                }
                !quoted && (character == '}' || character == ']') -> depth--
            }
        }
    }
}
