package io.mazewall.profiler.internal.hotspot

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal sealed interface HotSpotCollectionOutcome {
    val complete: Boolean
    val lossFree: Boolean

    data class Unknown(
        val reason: String,
        val unprovenSources: List<String> = emptyList(),
    ) : HotSpotCollectionOutcome {
        override val complete: Boolean get() = false
        override val lossFree: Boolean get() = false
    }

    data class ReconciledWithLoss(
        val totalLost: ULong,
        val sourceLosses: Map<String, ULong>,
        val identities: Map<String, String>,
    ) : HotSpotCollectionOutcome {
        override val complete: Boolean get() = true
        override val lossFree: Boolean get() = false
    }

    data class ReconciledWithoutLoss(
        val identities: Map<String, String>,
    ) : HotSpotCollectionOutcome {
        override val complete: Boolean get() = true
        override val lossFree: Boolean get() = true
    }
}

internal data class HotSpotSourceAdmission(
    val mapId: UInt,
    val possibleCpus: Int,
    val generation: ULong,
    val final: Boolean = false,
    val quiesced: Boolean = false,
    val joined: Boolean = false,
    val retired: Boolean = false,
    val error: String? = null,
    val overflow: Boolean = false,
    val coverage: Boolean = false,
)

internal data class HotSpotSourceAdmissionContext(
    val selectedEvents: HotSpotSourceAdmission? = null,
    val definitions: HotSpotSourceAdmission? = null,
    val isModelOnly: Boolean = false,
)

internal data class HotSpotAccumulatorCounts(
    val admittedOccurrences: ULong,
    val retainedOccurrences: ULong,
    val discardedOccurrences: ULong,
    val admittedRecoveries: ULong,
    val retainedRecoveries: ULong,
    val discardedRecoveries: ULong,
    val retainedBytes: Int,
    val resolved: ULong,
    val unavailable: ULong,
    val skipped: ULong,
    val allPaired: Boolean,
    val chargedBytes: Int? = null,
    val admittedPayloadBytes: Long,
    val discardedPayloadBytes: Long,
)

private const val MAX_LINUX_PID = 4194304
private const val MAX_LINUX_PID_ULONG = 4194304uL
private const val MAX_RETAINED_BYTES = 33554432
private const val SHA256_HEX_LENGTH = 64

private sealed interface AccountingStep<out T> {
    data class Ok<T>(
        val value: T,
    ) : AccountingStep<T>

    data class Fail(
        val unknown: HotSpotCollectionOutcome.Unknown,
    ) : AccountingStep<Nothing>
}

private inline fun <T, R> AccountingStep<T>.then(block: (T) -> AccountingStep<R>): AccountingStep<R> =
    when (this) {
        is AccountingStep.Ok -> block(value)
        is AccountingStep.Fail -> this
    }

private data class AccountingWork(
    val sessionId: String,
    val pid: Int,
    val startTicks: ULong,
    val selectedAdmission: HotSpotSourceAdmission? = null,
    val defAdmission: HotSpotSourceAdmission? = null,
    val isModelOnly: Boolean = false,
    val retentionLoss: ULong = 0uL,
    val envelope: JsonObject? = null,
    val sources: JsonObject? = null,
    val selectedLost: ULong = 0uL,
    val selectedMapId: UInt = 0u,
    val enrichmentLost: ULong = 0uL,
    val defLost: ULong = 0uL,
    val defMapId: UInt = 0u,
)

private fun parseStrictULong(element: JsonElement?): ULong? {
    val p = element as? JsonPrimitive ?: return null
    if (p.isString || p.content.isEmpty() || p.content.any { it !in '0'..'9' }) return null
    return p.content.toULongOrNull()
}

private fun parseCanonicalOrAlias(
    obj: JsonObject,
    canonicalKey: String,
    aliasKey: String,
): ULong? {
    val hasCanonical = canonicalKey in obj
    val hasAlias = aliasKey in obj
    val canonicalVal = if (hasCanonical) parseStrictULong(obj[canonicalKey]) else null
    val aliasVal = if (hasAlias) parseStrictULong(obj[aliasKey]) else null
    val malformed = (hasCanonical && canonicalVal == null) || (hasAlias && aliasVal == null)
    val conflicting = canonicalVal != null && aliasVal != null && canonicalVal != aliasVal
    return if ((!hasCanonical && !hasAlias) || malformed || conflicting) null else (canonicalVal ?: aliasVal)
}

private fun checkedAdd(
    a: ULong,
    b: ULong,
): ULong? {
    val sum = a + b
    return if (sum < a) null else sum
}

private fun requireReceiptBoolean(
    sourceName: String,
    sourceJson: JsonObject,
    key: String,
    expectedValue: Boolean,
    admissionValue: Boolean,
    mismatchLabel: String = key,
): HotSpotCollectionOutcome.Unknown? {
    val actual = (sourceJson[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    return when {
        actual == null ->
            HotSpotCollectionOutcome.Unknown("$sourceName receipt $key missing or not boolean", listOf(sourceName))
        actual != expectedValue || actual != admissionValue ->
            HotSpotCollectionOutcome.Unknown(
                "$sourceName receipt $mismatchLabel contradiction: expected $admissionValue, got $actual",
                listOf(sourceName),
            )
        else -> null
    }
}

private fun validateReceiptErrorFact(
    sourceName: String,
    sourceJson: JsonObject,
    admission: HotSpotSourceAdmission,
): HotSpotCollectionOutcome.Unknown? {
    val errElem = if (sourceJson.containsKey("error")) sourceJson["error"] else null
    val malformed = sourceJson.containsKey("error") && errElem !is JsonNull && !(errElem is JsonPrimitive && errElem.isString)
    val receiptErrStr = if (errElem is JsonPrimitive && errElem.isString) errElem.content.takeIf { it.isNotEmpty() } else null
    return when {
        !sourceJson.containsKey("error") ->
            HotSpotCollectionOutcome.Unknown("$sourceName receipt error missing", listOf(sourceName))
        malformed ->
            HotSpotCollectionOutcome.Unknown("$sourceName receipt error malformed or wrong type", listOf(sourceName))
        receiptErrStr != null ->
            HotSpotCollectionOutcome.Unknown("$sourceName receipt error: $receiptErrStr", listOf(sourceName))
        receiptErrStr != admission.error ->
            HotSpotCollectionOutcome.Unknown("$sourceName receipt error contradiction", listOf(sourceName))
        else -> null
    }
}

private fun validateReceiptFacts(
    sourceName: String,
    sourceJson: JsonObject,
    admission: HotSpotSourceAdmission,
): HotSpotCollectionOutcome.Unknown? =
    listOfNotNull(
        requireReceiptBoolean(sourceName, sourceJson, "final", true, admission.final, "finality"),
        requireReceiptBoolean(sourceName, sourceJson, "quiesced", true, admission.quiesced),
        requireReceiptBoolean(sourceName, sourceJson, "joined", true, admission.joined),
        requireReceiptBoolean(sourceName, sourceJson, "retired", true, admission.retired),
        requireReceiptBoolean(sourceName, sourceJson, "coverage", true, admission.coverage),
        requireReceiptBoolean(sourceName, sourceJson, "overflow", false, admission.overflow),
        validateReceiptErrorFact(sourceName, sourceJson, admission),
    ).firstOrNull()

internal object HotSpotCollectionAccounting {
    fun evaluate(
        summaryObject: JsonObject?,
        wireReceipt: HotSpotWireReceipt?,
        counts: HotSpotAccumulatorCounts,
        expectedSessionId: String,
        expectedPid: Int? = null,
        expectedStartTicks: ULong? = null,
        physicalDrainComplete: Boolean,
        terminalAccountingComplete: Boolean,
        admissionContext: HotSpotSourceAdmissionContext? = null,
    ): HotSpotCollectionOutcome {
        val result = validateTarget(expectedSessionId, expectedPid, expectedStartTicks)
            .then { work -> validateAdmissions(work, admissionContext) }
            .then { work -> validateLocalRetention(work, physicalDrainComplete, terminalAccountingComplete, counts) }
            .then { work -> validateEnvelopeIdentity(work, summaryObject) }
            .then { work -> validateWireAndPayload(work, wireReceipt, counts) }
            .then { work -> validateSourcesObject(work) }
            .then { work -> validateSelectedEvents(work, counts) }
            .then { work -> validateEnrichment(work, counts) }
            .then { work -> validateDefinitions(work) }
        return when (result) {
            is AccountingStep.Ok -> result.value
            is AccountingStep.Fail -> result.unknown
        }
    }

    private fun validateTarget(
        expectedSessionId: String,
        expectedPid: Int?,
        expectedStartTicks: ULong?,
    ): AccountingStep<AccountingWork> {
        val pid = expectedPid?.takeIf { it in 1..MAX_LINUX_PID }
        return when {
            expectedSessionId.isBlank() ->
                AccountingStep.Fail(HotSpotCollectionOutcome.Unknown("expectedSessionId cannot be blank", listOf("target")))
            pid == null ->
                AccountingStep.Fail(
                    HotSpotCollectionOutcome.Unknown("expectedPid missing or out of valid range (1..$MAX_LINUX_PID)", listOf("target")),
                )
            expectedStartTicks == null ->
                AccountingStep.Fail(HotSpotCollectionOutcome.Unknown("expectedStartTicks missing", listOf("target")))
            else -> AccountingStep.Ok(AccountingWork(expectedSessionId, pid, expectedStartTicks))
        }
    }

    private fun validateSingleAdmission(
        sourceName: String,
        admission: HotSpotSourceAdmission,
    ): HotSpotCollectionOutcome.Unknown? =
        when {
            admission.mapId == 0u || admission.possibleCpus <= 0 ->
                HotSpotCollectionOutcome.Unknown("$sourceName admission mapId or possibleCpus non-positive", listOf(sourceName))
            !admission.final || !admission.quiesced || !admission.joined || !admission.retired ->
                HotSpotCollectionOutcome.Unknown("$sourceName admission not final/quiesced/joined/retired", listOf(sourceName))
            !admission.error.isNullOrEmpty() ->
                HotSpotCollectionOutcome.Unknown("$sourceName admission error: ${admission.error}", listOf(sourceName))
            admission.overflow ->
                HotSpotCollectionOutcome.Unknown("$sourceName admission indicates overflow", listOf(sourceName))
            !admission.coverage ->
                HotSpotCollectionOutcome.Unknown("$sourceName admission coverage not established", listOf(sourceName))
            else -> null
        }

    private fun validateAdmissions(
        work: AccountingWork,
        admissionContext: HotSpotSourceAdmissionContext?,
    ): AccountingStep<AccountingWork> {
        val selected = admissionContext?.selectedEvents
        val definitions = admissionContext?.definitions
        val failure = when {
            admissionContext == null ->
                HotSpotCollectionOutcome.Unknown("source admission context absent", listOf("sources"))
            selected == null ->
                HotSpotCollectionOutcome.Unknown("selected_events admission absent", listOf("selected_events"))
            definitions == null ->
                HotSpotCollectionOutcome.Unknown("definitions admission absent", listOf("definitions"))
            else ->
                validateSingleAdmission("selected_events", selected)
                    ?: validateSingleAdmission("definitions", definitions)
        }
        return if (failure != null) {
            AccountingStep.Fail(failure)
        } else {
            val ctx = checkNotNull(admissionContext)
            AccountingStep.Ok(work.copy(selectedAdmission = selected, defAdmission = definitions, isModelOnly = ctx.isModelOnly))
        }
    }

    private fun validateRetainedBytes(counts: HotSpotAccumulatorCounts): HotSpotCollectionOutcome.Unknown? {
        val independentCharge = counts.chargedBytes
        return when {
            independentCharge == null ->
                HotSpotCollectionOutcome.Unknown("independent retained byte charge absent", listOf("retention"))
            counts.retainedBytes != independentCharge ->
                HotSpotCollectionOutcome.Unknown(
                    "retainedBytes (${counts.retainedBytes}) does not match independent charge ($independentCharge)",
                    listOf("retention"),
                )
            independentCharge !in 0..MAX_RETAINED_BYTES ->
                HotSpotCollectionOutcome.Unknown("retained byte charge out of valid range: $independentCharge", listOf("retention"))
            counts.retainedOccurrences == 0uL && counts.retainedRecoveries == 0uL && independentCharge != 0 ->
                HotSpotCollectionOutcome.Unknown("retainedBytes must be zero when zero records are retained", listOf("retention"))
            else -> null
        }
    }

    private fun validateLocalRetention(
        work: AccountingWork,
        physicalDrainComplete: Boolean,
        terminalAccountingComplete: Boolean,
        counts: HotSpotAccumulatorCounts,
    ): AccountingStep<AccountingWork> {
        val expectedOcc = checkedAdd(counts.retainedOccurrences, counts.discardedOccurrences)
        val expectedRec = checkedAdd(counts.retainedRecoveries, counts.discardedRecoveries)
        val retentionLoss = checkedAdd(counts.discardedOccurrences, counts.discardedRecoveries)
        val failure = when {
            !physicalDrainComplete -> HotSpotCollectionOutcome.Unknown("physical drain incomplete")
            !terminalAccountingComplete -> HotSpotCollectionOutcome.Unknown("terminal accounting incomplete")
            !counts.allPaired -> HotSpotCollectionOutcome.Unknown("unpaired event/terminal records remain")
            expectedOcc == null -> HotSpotCollectionOutcome.Unknown("retention occurrences count overflow", listOf("retention"))
            counts.admittedOccurrences != expectedOcc -> HotSpotCollectionOutcome.Unknown("retention occurrence accounting mismatch", listOf("retention"))
            expectedRec == null -> HotSpotCollectionOutcome.Unknown("retention recoveries count overflow", listOf("retention"))
            counts.admittedRecoveries != expectedRec -> HotSpotCollectionOutcome.Unknown("retention recovery accounting mismatch", listOf("retention"))
            retentionLoss == null -> HotSpotCollectionOutcome.Unknown("retention loss count overflow", listOf("retention"))
            else -> validateRetainedBytes(counts)
        }
        return if (failure != null) AccountingStep.Fail(failure) else AccountingStep.Ok(work.copy(retentionLoss = checkNotNull(retentionLoss)))
    }

    private fun validateEnvelopeIdentity(
        work: AccountingWork,
        summaryObject: JsonObject?,
    ): AccountingStep<AccountingWork> {
        if (summaryObject == null) {
            return AccountingStep.Fail(HotSpotCollectionOutcome.Unknown("summary object absent"))
        }
        val envelope = summaryObject["production_accounting"] as? JsonObject
            ?: return AccountingStep.Fail(HotSpotCollectionOutcome.Unknown("production_accounting extension absent"))
        val schema = parseStrictULong(envelope["schema"])
        val session = (envelope["session_id"] as? JsonPrimitive)?.content
        val failure = when {
            schema == null -> HotSpotCollectionOutcome.Unknown("production_accounting schema missing or invalid")
            schema != 1uL -> HotSpotCollectionOutcome.Unknown("unsupported production_accounting schema $schema")
            session == null -> HotSpotCollectionOutcome.Unknown("production_accounting session_id missing")
            session != work.sessionId ->
                HotSpotCollectionOutcome.Unknown("session ID mismatch: expected ${work.sessionId}, got $session", listOf("target"))
            else -> validateEnvelopeProcess(work, envelope)
        }
        return if (failure != null) AccountingStep.Fail(failure) else AccountingStep.Ok(work.copy(envelope = envelope))
    }

    private fun validateEnvelopeProcess(
        work: AccountingWork,
        envelope: JsonObject,
    ): HotSpotCollectionOutcome.Unknown? {
        val envelopePid = parseStrictULong(envelope["host_pid"])?.takeIf { it in 1uL..MAX_LINUX_PID_ULONG }?.toInt()
        val envelopeBirth = parseStrictULong(envelope["process_start_ticks"])
        return when {
            envelopePid == null ->
                HotSpotCollectionOutcome.Unknown("host_pid missing, negative or out of valid PID range in production_accounting", listOf("target"))
            envelopePid != work.pid ->
                HotSpotCollectionOutcome.Unknown("host PID mismatch: expected ${work.pid}, got $envelopePid", listOf("target"))
            envelopeBirth == null ->
                HotSpotCollectionOutcome.Unknown("process_start_ticks missing or negative in production_accounting", listOf("target"))
            envelopeBirth != work.startTicks ->
                HotSpotCollectionOutcome.Unknown("process start ticks mismatch: expected ${work.startTicks}, got $envelopeBirth", listOf("target"))
            else -> null
        }
    }

    private fun validateWireAndPayload(
        work: AccountingWork,
        wireReceipt: HotSpotWireReceipt?,
        counts: HotSpotAccumulatorCounts,
    ): AccountingStep<AccountingWork> {
        if (wireReceipt == null || !wireReceipt.validEof) {
            return AccountingStep.Fail(HotSpotCollectionOutcome.Unknown("wire receipt absent or EOF invalid", listOf("wire")))
        }
        val wirePrefix = checkNotNull(work.envelope)["wire_prefix"] as? JsonObject
            ?: return AccountingStep.Fail(HotSpotCollectionOutcome.Unknown("wire_prefix missing in production_accounting", listOf("wire")))
        val prefixRecords = parseStrictULong(wirePrefix["records"])
        val prefixBytes = parseStrictULong(wirePrefix["bytes"])
        val prefixSha = (wirePrefix["sha256"] as? JsonPrimitive)?.content
        val wireMatches = prefixRecords == wireReceipt.records && prefixBytes == wireReceipt.bytes && prefixSha == wireReceipt.sha256
        val failure = when {
            prefixRecords == null -> HotSpotCollectionOutcome.Unknown("wire_prefix records missing or negative", listOf("wire"))
            prefixBytes == null -> HotSpotCollectionOutcome.Unknown("wire_prefix bytes missing or negative", listOf("wire"))
            prefixSha == null -> HotSpotCollectionOutcome.Unknown("wire_prefix sha256 missing", listOf("wire"))
            prefixSha.length != SHA256_HEX_LENGTH -> HotSpotCollectionOutcome.Unknown("wire_prefix sha256 malformed", listOf("wire"))
            !wireMatches ->
                HotSpotCollectionOutcome.Unknown(
                    "wire prefix mismatch: expected ${wireReceipt.records} recs/${wireReceipt.bytes} bytes/${wireReceipt.sha256}, " +
                        "got $prefixRecords recs/$prefixBytes bytes/$prefixSha",
                    listOf("wire"),
                )
            else -> validatePayloadBytes(counts)
        }
        return if (failure != null) AccountingStep.Fail(failure) else AccountingStep.Ok(work)
    }

    private fun validatePayloadBytes(counts: HotSpotAccumulatorCounts): HotSpotCollectionOutcome.Unknown? {
        if (counts.admittedPayloadBytes < 0 || counts.discardedPayloadBytes < 0) {
            return HotSpotCollectionOutcome.Unknown("negative payload byte count", listOf("retention"))
        }
        val accountedPayloadBytes = try {
            Math.addExact(counts.retainedBytes.toLong(), counts.discardedPayloadBytes)
        } catch (_: ArithmeticException) {
            return HotSpotCollectionOutcome.Unknown("payload byte count overflow", listOf("retention"))
        }
        return if (counts.admittedPayloadBytes != accountedPayloadBytes) {
            HotSpotCollectionOutcome.Unknown("payload byte accounting mismatch", listOf("retention"))
        } else {
            null
        }
    }

    private fun validateSourcesObject(work: AccountingWork): AccountingStep<AccountingWork> {
        val sources = checkNotNull(work.envelope)["sources"] as? JsonObject
        return if (sources == null) {
            AccountingStep.Fail(
                HotSpotCollectionOutcome.Unknown("sources missing in production_accounting", listOf("selected_events", "definitions", "enrichment")),
            )
        } else {
            AccountingStep.Ok(work.copy(sources = sources))
        }
    }

    private fun validateSourceIdentity(
        sourceName: String,
        sourceJson: JsonObject,
        admission: HotSpotSourceAdmission,
    ): AccountingStep<UInt> {
        val rawMapId = parseCanonicalOrAlias(sourceJson, "map_id", "mapId")
        val rawCpus = parseCanonicalOrAlias(sourceJson, "possible_cpus", "possibleCpus")
        val gen = parseCanonicalOrAlias(sourceJson, "generation", "generation")
        val mapIdOk = rawMapId != null && rawMapId in 1uL..UInt.MAX_VALUE.toULong()
        val cpusOk = rawCpus != null && rawCpus in 1uL..Int.MAX_VALUE.toULong()
        val failure = when {
            rawMapId == null -> HotSpotCollectionOutcome.Unknown("$sourceName map_id missing or malformed", listOf(sourceName))
            !mapIdOk -> HotSpotCollectionOutcome.Unknown("$sourceName map_id out of uint32 range: $rawMapId", listOf(sourceName))
            rawMapId.toUInt() != admission.mapId ->
                HotSpotCollectionOutcome.Unknown("$sourceName map_id mismatch: expected ${admission.mapId}, got ${rawMapId.toUInt()}", listOf(sourceName))
            rawCpus == null -> HotSpotCollectionOutcome.Unknown("$sourceName possible_cpus missing or malformed", listOf(sourceName))
            !cpusOk -> HotSpotCollectionOutcome.Unknown("$sourceName possible_cpus out of int32 range: $rawCpus", listOf(sourceName))
            rawCpus.toInt() != admission.possibleCpus ->
                HotSpotCollectionOutcome.Unknown("$sourceName possible_cpus mismatch: expected ${admission.possibleCpus}, got ${rawCpus.toInt()}", listOf(sourceName))
            gen == null -> HotSpotCollectionOutcome.Unknown("$sourceName generation missing or malformed", listOf(sourceName))
            gen != admission.generation ->
                HotSpotCollectionOutcome.Unknown("$sourceName generation mismatch: expected ${admission.generation}, got $gen", listOf(sourceName))
            else -> validateReceiptFacts(sourceName, sourceJson, admission)
        }
        return if (failure != null) AccountingStep.Fail(failure) else AccountingStep.Ok(checkNotNull(rawMapId).toUInt())
    }

    private fun validateSelectedEvents(
        work: AccountingWork,
        counts: HotSpotAccumulatorCounts,
    ): AccountingStep<AccountingWork> {
        val selected = checkNotNull(work.sources)["selected_events"] as? JsonObject
            ?: return AccountingStep.Fail(HotSpotCollectionOutcome.Unknown("selected_events receipt missing", listOf("selected_events")))
        val complete = (selected["complete"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: false
        if (!complete) {
            return AccountingStep.Fail(HotSpotCollectionOutcome.Unknown("selected_events accounting incomplete", listOf("selected_events")))
        }
        return validateSelectedCounts(selected, counts.admittedOccurrences)
            .then { lost ->
                validateSourceIdentity("selected_events", selected, checkNotNull(work.selectedAdmission))
                    .then { mapId -> AccountingStep.Ok(work.copy(selectedLost = lost, selectedMapId = mapId)) }
            }
    }

    private fun validateSelectedCounts(
        selected: JsonObject,
        admittedOccurrences: ULong,
    ): AccountingStep<ULong> {
        val attempted = parseCanonicalOrAlias(selected, "attempted", "attempted")
        val submitted = parseCanonicalOrAlias(selected, "submitted", "submitted")
        val received = parseCanonicalOrAlias(selected, "received", "received")
        val lost = parseCanonicalOrAlias(selected, "reserve_failed", "reserveFailed")
        val sum = if (submitted != null && lost != null) checkedAdd(submitted, lost) else null
        val failure = when {
            attempted == null -> HotSpotCollectionOutcome.Unknown("selected_events attempted missing or malformed", listOf("selected_events"))
            submitted == null -> HotSpotCollectionOutcome.Unknown("selected_events submitted missing or malformed", listOf("selected_events"))
            received == null -> HotSpotCollectionOutcome.Unknown("selected_events received missing or malformed", listOf("selected_events"))
            lost == null -> HotSpotCollectionOutcome.Unknown("selected_events reserve_failed missing, malformed or negative", listOf("selected_events"))
            sum == null -> HotSpotCollectionOutcome.Unknown("selected_events conservation sum overflow", listOf("selected_events"))
            attempted != sum ->
                HotSpotCollectionOutcome.Unknown(
                    "selected_events conservation violated: attempted=$attempted != submitted=$submitted + reserve_failed=$lost",
                    listOf("selected_events"),
                )
            submitted != received ->
                HotSpotCollectionOutcome.Unknown("selected_events submitted=$submitted != received=$received", listOf("selected_events"))
            received != admittedOccurrences ->
                HotSpotCollectionOutcome.Unknown("selected received does not match admitted occurrences", listOf("selected_events"))
            else -> null
        }
        return if (failure != null) AccountingStep.Fail(failure) else AccountingStep.Ok(checkNotNull(lost))
    }

    private fun validateEnrichment(
        work: AccountingWork,
        counts: HotSpotAccumulatorCounts,
    ): AccountingStep<AccountingWork> {
        val enrichment = checkNotNull(work.sources)["enrichment"] as? JsonObject
            ?: return AccountingStep.Fail(HotSpotCollectionOutcome.Unknown("enrichment receipt missing", listOf("enrichment")))
        val received = parseCanonicalOrAlias(enrichment, "received", "attempted")
        val resolved = parseCanonicalOrAlias(enrichment, "resolved", "submitted")
        val unavailable = parseCanonicalOrAlias(enrichment, "unavailable", "reserve_failed")
        val skipped = parseCanonicalOrAlias(enrichment, "skipped", "skipped")
        val failure = when {
            received == null -> HotSpotCollectionOutcome.Unknown("enrichment received missing or malformed", listOf("enrichment"))
            received != counts.admittedOccurrences ->
                HotSpotCollectionOutcome.Unknown("enrichment received does not match admitted occurrences", listOf("enrichment"))
            resolved == null -> HotSpotCollectionOutcome.Unknown("enrichment resolved missing or malformed", listOf("enrichment"))
            unavailable == null -> HotSpotCollectionOutcome.Unknown("enrichment unavailable missing or malformed", listOf("enrichment"))
            skipped == null -> HotSpotCollectionOutcome.Unknown("enrichment skipped missing or malformed", listOf("enrichment"))
            else -> null
        }
        return if (failure != null) {
            AccountingStep.Fail(failure)
        } else {
            validateEnrichmentEquation(work, counts, checkNotNull(received), checkNotNull(resolved), checkNotNull(unavailable), checkNotNull(skipped))
        }
    }

    private fun validateEnrichmentEquation(
        work: AccountingWork,
        counts: HotSpotAccumulatorCounts,
        received: ULong,
        resolved: ULong,
        unavailable: ULong,
        skipped: ULong,
    ): AccountingStep<AccountingWork> {
        val sum1 = checkedAdd(resolved, unavailable)
        val sum = sum1?.let { checkedAdd(it, skipped) }
        val lost = checkedAdd(unavailable, skipped)
        val failure = when {
            sum1 == null || sum == null -> HotSpotCollectionOutcome.Unknown("enrichment sum overflow", listOf("enrichment"))
            sum != received ->
                HotSpotCollectionOutcome.Unknown("enrichment equation invalid: $resolved + $unavailable + $skipped != $received", listOf("enrichment"))
            resolved != counts.resolved ->
                HotSpotCollectionOutcome.Unknown("enrichment resolved ($resolved) mismatch with local accumulator (${counts.resolved})", listOf("enrichment"))
            unavailable != counts.unavailable ->
                HotSpotCollectionOutcome.Unknown("enrichment unavailable ($unavailable) mismatch with local accumulator (${counts.unavailable})", listOf("enrichment"))
            skipped != counts.skipped ->
                HotSpotCollectionOutcome.Unknown("enrichment skipped ($skipped) mismatch with local accumulator (${counts.skipped})", listOf("enrichment"))
            lost == null -> HotSpotCollectionOutcome.Unknown("enrichment loss overflow", listOf("enrichment"))
            else -> null
        }
        return if (failure != null) AccountingStep.Fail(failure) else AccountingStep.Ok(work.copy(enrichmentLost = checkNotNull(lost)))
    }

    private fun validateDefinitions(work: AccountingWork): AccountingStep<HotSpotCollectionOutcome> {
        val definitions = checkNotNull(work.sources)["definitions"] as? JsonObject
        val complete = (definitions?.get("complete") as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            ?: ((definitions?.get("final") as? JsonObject)?.get("complete") as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            ?: false
        val defLost = definitions?.let { parseCanonicalOrAlias(it, "lost", "lost") }
        val preFailure = when {
            definitions == null -> HotSpotCollectionOutcome.Unknown("definitions receipt missing", listOf("definitions"))
            !complete -> HotSpotCollectionOutcome.Unknown("definitions accounting incomplete", listOf("definitions"))
            defLost == null -> HotSpotCollectionOutcome.Unknown("definitions lost missing or malformed", listOf("definitions"))
            else -> null
        }
        if (preFailure != null) return AccountingStep.Fail(preFailure)
        return validateSourceIdentity("definitions", checkNotNull(definitions), checkNotNull(work.defAdmission))
            .then { defMapId ->
                if (!work.isModelOnly) {
                    AccountingStep.Fail(HotSpotCollectionOutcome.Unknown("independent native source admission unavailable", listOf("definitions")))
                } else {
                    reconcileTotal(work.copy(defLost = checkNotNull(defLost), defMapId = defMapId))
                }
            }
    }

    private fun reconcileTotal(work: AccountingWork): AccountingStep<HotSpotCollectionOutcome> {
        val l1 = checkedAdd(work.selectedLost, work.enrichmentLost)
        val l2 = l1?.let { checkedAdd(it, work.defLost) }
        val totalLost = l2?.let { checkedAdd(it, work.retentionLoss) }
        val overflowFailure = when {
            l1 == null -> HotSpotCollectionOutcome.Unknown("total loss overflow", listOf("selected_events", "enrichment"))
            l2 == null -> HotSpotCollectionOutcome.Unknown("total loss overflow", listOf("definitions"))
            totalLost == null -> HotSpotCollectionOutcome.Unknown("total loss overflow", listOf("retention"))
            else -> null
        }
        if (overflowFailure != null) return AccountingStep.Fail(overflowFailure)

        val identities = mapOf(
            "session" to work.sessionId,
            "pid" to work.pid.toString(),
            "birth" to work.startTicks.toString(),
            "selected_events_map_id" to work.selectedMapId.toString(),
            "definitions_map_id" to work.defMapId.toString(),
            "model_only" to "true",
        )
        val sourceLosses = mapOf(
            "selected_events" to work.selectedLost,
            "enrichment" to work.enrichmentLost,
            "definitions" to work.defLost,
            "retention" to work.retentionLoss,
        )
        val outcome = if (checkNotNull(totalLost) == 0uL) {
            HotSpotCollectionOutcome.ReconciledWithoutLoss(identities)
        } else {
            HotSpotCollectionOutcome.ReconciledWithLoss(totalLost, sourceLosses, identities)
        }
        return AccountingStep.Ok(outcome)
    }
}
