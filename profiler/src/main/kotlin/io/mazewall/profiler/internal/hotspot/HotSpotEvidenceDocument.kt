package io.mazewall.profiler.internal.hotspot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val MAX_DOCUMENT_CHARS = 64 * 1024 * 1024
private const val MAX_DOCUMENT_DEPTH = 64
private const val MAX_SESSION_LENGTH = 128
private const val SHA256_HEX_LENGTH = 64

/** Explicit diagnostic serialization preserves raw provider fields and independently bounded retention facts. */
internal object HotSpotEvidenceDocument {
    fun encode(value: StructuredProfileEvidence): String =
        buildJsonObject {
        value.collectionReceipt?.let {
            validateReceipt(
                it,
                value.records.size,
                value.recoveries.size,
                value.discardedOccurrences,
                value.discardedRecoveries,
                value.retainedBytes,
                value.finalized,
            )
            put("collectionReceipt", encodeReceipt(it))
        }
        put("formatVersion", 1)
        put("sessionId", value.sessionId)
        put("finalized", value.finalized)
        put("pendingOccurrences", JsonPrimitive(value.pendingOccurrences.toString().toBigInteger()))
        put("discardedOccurrences", JsonPrimitive(value.discardedOccurrences.toString().toBigInteger()))
        put("discardedRecoveries", JsonPrimitive(value.discardedRecoveries.toString().toBigInteger()))
        put("retainedBytes", value.retainedBytes)
        put("collectionLossComplete", value.collectionLossComplete)
        put("policyEligibility", false)
        value.runtimeProfile?.let { put("runtimeProfile", it) }
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

private fun JsonObject.strictUlong(key: String): ULong {
    val primitive = this[key] as? JsonPrimitive
    checkHotSpotProtocol(primitive != null, "missing or non-primitive $key")
    val p = checkNotNull(primitive)
    checkHotSpotProtocol(!p.isString && p.content.isNotEmpty() && p.content.all { it in '0'..'9' }, "invalid uint64 $key")
    return p.content.toULongOrNull() ?: throw HotSpotProtocolException("invalid uint64 overflow $key")
}

private fun JsonObject.requireElement(
    key: String,
    missingMessage: String,
): JsonElement = this[key] ?: throw HotSpotProtocolException(missingMessage)

private fun JsonObject.strictUint(key: String): UInt {
    val u = strictUlong(key)
    if (u > UInt.MAX_VALUE.toULong()) throw HotSpotProtocolException("uint32 overflow $key")
    return u.toUInt()
}

private fun checkedAddULong(
    a: ULong,
    b: ULong,
    context: String,
): ULong {
    val sum = a + b
    if (sum < a) throw HotSpotProtocolException("arithmetic overflow in $context")
    return sum
}

    private fun encodeReceipt(receipt: HotSpotCollectionReceipt): JsonObject =
        buildJsonObject {
        fun number(value: ULong) = JsonPrimitive(value.toString().toBigInteger())
        put("schema", 1)
        put(
            "wireReceipt",
            receipt.wireReceipt?.let { wire ->
            buildJsonObject {
                put("records", number(wire.records))
                put("bytes", number(wire.bytes))
                put("sha256", wire.sha256)
                put("validEof", wire.validEof)
            }
        } ?: JsonNull,
        )
        val c = receipt.counts
        put(
            "counts",
            buildJsonObject {
            put("admittedOccurrences", number(c.admittedOccurrences))
            put("retainedOccurrences", number(c.retainedOccurrences))
            put("discardedOccurrences", number(c.discardedOccurrences))
            put("admittedRecoveries", number(c.admittedRecoveries))
            put("retainedRecoveries", number(c.retainedRecoveries))
            put("discardedRecoveries", number(c.discardedRecoveries))
            put("retainedBytes", c.retainedBytes)
            put("resolved", number(c.resolved))
            put("unavailable", number(c.unavailable))
            put("skipped", number(c.skipped))
            put("allPaired", c.allPaired)
            put("chargedBytes", c.chargedBytes?.let { JsonPrimitive(it) } ?: JsonNull)
            put("admittedPayloadBytes", c.admittedPayloadBytes)
            put("discardedPayloadBytes", c.discardedPayloadBytes)
        },
        )
    }

    private fun JsonObject.strictInt(key: String): Int {
        val value = strictUlong(key)
        if (value > Int.MAX_VALUE.toULong()) throw HotSpotProtocolException("int overflow $key")
        return value.toInt()
    }

    private fun JsonObject.strictLong(key: String): Long {
        val value = strictUlong(key)
        if (value > Long.MAX_VALUE.toULong()) throw HotSpotProtocolException("long overflow $key")
        return value.toLong()
    }

    private fun decodeReceipt(obj: JsonObject): HotSpotCollectionReceipt {
        checkHotSpotProtocol(obj.strictUlong("schema") == 1uL, "unsupported collection receipt schema")
        val wireElement = obj.requireElement("wireReceipt", "missing wireReceipt")
        val wire = if (wireElement == JsonNull) {
            null
        } else {
            checkHotSpotProtocol(wireElement is JsonObject, "invalid wireReceipt")
            val w = wireElement as JsonObject
            HotSpotWireReceipt(w.strictUlong("records"), w.strictUlong("bytes"), w.text("sha256"), w.boolean("validEof"))
        }
        val c = obj.objectField("counts")
        val charged = c.requireElement("chargedBytes", "missing chargedBytes")
        return HotSpotCollectionReceipt(
            wire,
            HotSpotAccumulatorCounts(
                admittedOccurrences = c.strictUlong("admittedOccurrences"),
                retainedOccurrences = c.strictUlong("retainedOccurrences"),
                discardedOccurrences = c.strictUlong("discardedOccurrences"),
                admittedRecoveries = c.strictUlong("admittedRecoveries"),
                retainedRecoveries = c.strictUlong("retainedRecoveries"),
                discardedRecoveries = c.strictUlong("discardedRecoveries"),
                retainedBytes = c.strictInt("retainedBytes"),
                resolved = c.strictUlong("resolved"),
                unavailable = c.strictUlong("unavailable"),
                skipped = c.strictUlong("skipped"),
                allPaired = c.boolean("allPaired"),
                chargedBytes = if (charged == JsonNull) null else c.strictInt("chargedBytes"),
                admittedPayloadBytes = c.strictLong("admittedPayloadBytes"),
                discardedPayloadBytes = c.strictLong("discardedPayloadBytes"),
            ),
        )
    }

    private fun validateFinalizedReceiptCounts(c: HotSpotAccumulatorCounts) {
        checkHotSpotProtocol(c.allPaired, "finalized receipt is unpaired")
        checkHotSpotProtocol(
            checkedAddULong(c.retainedOccurrences, c.discardedOccurrences, "receipt occurrences") == c.admittedOccurrences &&
                checkedAddULong(c.retainedRecoveries, c.discardedRecoveries, "receipt recoveries") == c.admittedRecoveries,
            "receipt count conservation mismatch",
        )
        checkHotSpotProtocol(
            c.admittedPayloadBytes >= c.discardedPayloadBytes &&
                c.admittedPayloadBytes - c.discardedPayloadBytes == c.retainedBytes.toLong(),
            "receipt payload byte mismatch",
        )
        checkHotSpotProtocol(c.chargedBytes == null || c.chargedBytes == c.retainedBytes, "receipt retained charge mismatch")
    }

    private fun validateReceipt(
        receipt: HotSpotCollectionReceipt,
        retainedOccurrences: Int,
        retainedRecoveries: Int,
        discardedOccurrences: ULong,
        discardedRecoveries: ULong,
        retainedBytes: Int,
        finalized: Boolean,
    ) {
        receipt.wireReceipt?.let { wire ->
            checkHotSpotProtocol(
                wire.sha256.length == SHA256_HEX_LENGTH && wire.sha256.all { it in '0'..'9' || it in 'a'..'f' },
                "invalid wire receipt hash",
            )
        }
        val c = receipt.counts
        checkHotSpotProtocol(
            c.retainedBytes >= 0 &&
                (c.chargedBytes == null || c.chargedBytes >= 0) &&
                c.admittedPayloadBytes >= 0 &&
                c.discardedPayloadBytes >= 0,
            "invalid receipt byte count",
        )
        checkHotSpotProtocol(
            c.retainedOccurrences == retainedOccurrences.toULong() &&
                c.retainedRecoveries == retainedRecoveries.toULong() &&
                c.discardedOccurrences == discardedOccurrences &&
                c.discardedRecoveries == discardedRecoveries &&
                c.retainedBytes == retainedBytes,
            "receipt retained facts mismatch",
        )
        if (finalized) {
            validateFinalizedReceiptCounts(c)
        }
    }

    private fun decodeOccurrences(
        records: JsonArray,
        session: String,
        limits: HotSpotReplayLimits,
    ): List<HotSpotOccurrence> {
        checkHotSpotProtocol(records.size <= limits.maxRetainedOccurrences, "report occurrence limit exceeded")
        val mapper = HotSpotWireMapper(limits)
        val ids = mutableSetOf<String>()
        return records.map { element ->
            checkHotSpotProtocol(element is JsonObject, "report occurrence is not an object")
            val item = element as JsonObject
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
    }

    private fun validateFinalizedReport(
        root: JsonObject,
        final: HotSpotReplaySummary,
        retainedOccurrences: Int,
        discardedOccurrences: ULong,
        discardedRecoveries: ULong,
        retained: UInt,
    ) {
        val expectedOccurrences = checkedAddULong(retainedOccurrences.toULong(), discardedOccurrences, "occurrence counts")
        checkHotSpotProtocol(
            final.received == expectedOccurrences,
            "summary received (${final.received}) does not match retained plus discarded occurrences ($expectedOccurrences)",
        )
        val terminalPart = checkedAddULong(final.resolved, final.unavailable, "terminal counts")
        val terminalSum = checkedAddULong(terminalPart, final.skipped, "terminal counts")
        checkHotSpotProtocol(terminalSum == final.received, "summary terminal counts do not match received")
        checkHotSpotProtocol(root.strictUlong("pendingOccurrences") == 0uL, "finalized document cannot have pending occurrences")
        if (final.received == 0uL && discardedRecoveries == 0uL && discardedOccurrences == 0uL) {
            checkHotSpotProtocol(retained == 0u, "retainedBytes must be zero when zero records are received")
        }
    }

    fun decode(text: String): StructuredProfileEvidence {
        require(text.length <= MAX_DOCUMENT_CHARS) { "Structured evidence report exceeds size limit" }
        checkDepth(text)
        val parsed = Json.parseToJsonElement(text)
        checkHotSpotProtocol(parsed is JsonObject, "evidence report is not an object")
        val root = parsed as JsonObject
        val serializedLossComplete = root.boolean("collectionLossComplete")
        checkHotSpotProtocol(root.strictUlong("formatVersion") == 1uL, "unsupported evidence report version")
        checkHotSpotProtocol(!root.containsKey("policyEligibility") || !root.boolean("policyEligibility"), "diagnostic report cannot grant policy eligibility")
        val runtimeProfile = root.optionalText("runtimeProfile")
        checkHotSpotProtocol(runtimeProfile == null || runtimeProfile in setOf(HotSpotCaptureExpectation.Strict.runtimeProfile, "otel_hotspot25_observed_v1"), "unsupported diagnostic runtime profile")
        val session = root.text("sessionId")
        checkHotSpotProtocol(session.length in 1..MAX_SESSION_LENGTH, "invalid report session identity")
        val limits = HotSpotReplayLimits()
        val mapped = decodeOccurrences(root.array("records"), session, limits)
        val summary = root.objectField("summary")
        val final = HotSpotReplaySummary(
            summary.strictUlong("received"),
            summary.strictUlong("resolved"),
            summary.strictUlong("unavailable"),
            summary.strictUlong("skipped"),
            summary.boolean("terminalAccountingComplete"),
            summary.boolean("physicalDrainComplete"),
            summary.optionalUlong("observedEventLoss"),
            summary.objectField("raw").toString(),
        )
        val recoveries = root.array("recoveries")
        checkHotSpotProtocol(recoveries.size <= limits.maxTrackedOccurrences, "report recovery limit exceeded")
        val retained = root.strictUint("retainedBytes")
        checkHotSpotProtocol(retained <= limits.maxRetainedBytes.toUInt(), "report retention budget exceeded")
        val discardedOccurrences = root.strictUlong("discardedOccurrences")
        val discardedRecoveries = root.strictUlong("discardedRecoveries")
        val finalized = root.boolean("finalized")
        if (finalized) {
            validateFinalizedReport(root, final, mapped.size, discardedOccurrences, discardedRecoveries, retained)
        }
        checkHotSpotProtocol(!serializedLossComplete, "serialized collectionLossComplete true cannot be established from document")
        val accountingOutcome = HotSpotCollectionOutcome.Unknown("serialized collectionLossComplete false")
        val receipt = root["collectionReceipt"]?.let { element ->
            checkHotSpotProtocol(element is JsonObject, "invalid collectionReceipt")
            decodeReceipt(element as JsonObject).also {
                validateReceipt(it, mapped.size, recoveries.size, discardedOccurrences, discardedRecoveries, retained.toInt(), finalized)
            }
        }
        return StructuredProfileEvidence(
            sessionId = session,
            records = mapped,
            summary = final,
            recoveries = recoveries.map(::recoveryText),
            retention = HotSpotRetentionFacts(
                discardedOccurrences = discardedOccurrences,
                retainedBytes = retained.toInt(),
                discardedRecoveries = discardedRecoveries,
                finalized = finalized,
                pendingOccurrences = root.ulong("pendingOccurrences"),
            ),
            runtimeProfile = runtimeProfile,
            collectionAccounting = accountingOutcome,
            collectionReceipt = receipt,
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
