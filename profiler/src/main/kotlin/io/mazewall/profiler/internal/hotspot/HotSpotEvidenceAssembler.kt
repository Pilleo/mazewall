package io.mazewall.profiler.internal.hotspot

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.InputStream

internal class HotSpotEvidenceAssembler(
    private val limits: HotSpotReplayLimits = HotSpotReplayLimits(),
    private val expectation: HotSpotCaptureExpectation? = null,
) {
    fun read(input: InputStream): StructuredProfileEvidence {
        val replay = begin()
        val receipt = HotSpotWireReader(limits).read(input, replay::accept)
        return replay.finish(receipt)
    }

    fun begin(): Accumulator = Accumulator()

    private class Tracked(
        val retain: Boolean,
    ) {
        var event: HotSpotEvent? = null
        var evidence: HotSpotStackEvidence? = null
        var seenEvent = false
        var seenTerminal = false
        var bytes = 0
        var retained = retain
        var correlationId = 0uL
        var cpu = 0u
        var recovered = false
    }

    internal inner class Accumulator {
        private var session: String? = null
        private var target: Pair<Int, ULong>? = null
        private val tracked = linkedMapOf<String, Tracked>()
        private val eventOrder = mutableListOf<String>()
        private val recoveries = mutableListOf<String>()
        private var recoveryCount = 0
        private var recoveryInvalidations = 0uL
        private var recoveryErrors = 0uL
        private var discardedRecoveries = 0uL
        private var summary: HotSpotReplaySummary? = null
        private var retainedCount = 0
        private var bytes = 0
        private var admittedPayloadBytes = 0L
        private var discardedPayloadBytes = 0L
        private var payloadFailure: String? = null
        private var resolved = 0uL
        private var unavailable = 0uL
        private var skipped = 0uL
        private val mapper = HotSpotWireMapper(limits)
        val hasSession: Boolean get() = session != null

        fun accept(row: HotSpotWireRecord) {
            checkPayloadFailure()
            checkHotSpotProtocol(summary == null, "record after terminal summary")
            checkPayloadCapacity(row)
            if (session == null) session = row.sessionId
            checkHotSpotProtocol(session == row.sessionId, "collector session changed")
            when (row.kind) {
                "collector_attached" -> Unit
                "event" -> acceptEvent(row)
                "enrichment" -> acceptTerminal(row)
                "recovery" -> {
                    if (++recoveryCount > limits.maxTrackedOccurrences) throw HotSpotProtocolException("recovery record limit exceeded")
                    if (expectation is HotSpotCaptureExpectation.Observed) {
                        acceptRecovery(row)
                    } else {
                        row.objectValue.objectField("recovery")
                    }
                    val discard = row.byteSize > limits.maxRetainedBytes - bytes
                    val nextBytes = if (discard) bytes else checkedRetainedAdd(bytes, row.byteSize)
                    accountPayload(row.byteSize, if (discard) row.byteSize.toLong() else 0L)
                    if (discard) {
                        discardedRecoveries++
                    } else {
                        recoveries.add(row.rawJson)
                        bytes = nextBytes
                    }
                }
                "session_summary" -> {
                    if (expectation is HotSpotCaptureExpectation.Observed) HotSpotObservedWire.summary(row, expectation.target)
                    summary = mapper.summary(row)
                }
                else -> throw HotSpotProtocolException("unsupported collector record kind ${row.kind}")
            }
        }

        private fun occurrence(row: HotSpotWireRecord): Tracked {
            val id = row.eventId ?: throw HotSpotProtocolException("occurrence record lacks event ID")
            return tracked[id] ?: run {
                if (tracked.size >= limits.maxTrackedOccurrences) throw HotSpotProtocolException("tracked occurrence limit exceeded")
                val retained = retainedCount < limits.maxRetainedOccurrences
                if (retained) retainedCount++
                Tracked(retained).also { tracked[id] = it }
            }
        }

        private fun payloadError(): Nothing {
            val message = "invalid or overflowing payload byte accounting"
            payloadFailure = message
            throw HotSpotProtocolException(message)
        }

        private fun checkPayloadFailure() {
            payloadFailure?.let { throw HotSpotProtocolException(it) }
        }

        private fun validateLedgerState(size: Int) {
            if (size < 0 || bytes !in 0..limits.maxRetainedBytes || admittedPayloadBytes < 0 || discardedPayloadBytes < 0) {
                payloadError()
            }
        }

        private fun verifyPayloadArithmetic(
            size: Int,
            prior: Int,
            discarded: Long,
            retainRow: Boolean,
        ) {
            try {
                Math.addExact(admittedPayloadBytes, size.toLong())
                Math.addExact(discardedPayloadBytes, discarded)
                if (retainRow) {
                    Math.addExact(bytes, size)
                    Math.addExact(prior, size)
                }
            } catch (_: ArithmeticException) {
                payloadError()
            }
        }

        private fun checkOccurrencePayload(
            record: Tracked?,
            size: Int,
        ) {
            val prior = record?.bytes ?: 0
            if (prior !in 0..bytes) payloadError()
            val retained = record?.retained ?: (retainedCount < limits.maxRetainedOccurrences)
            if (!retained && prior != 0) payloadError()
            val evicted = retained && size > limits.maxRetainedBytes - bytes
            if (evicted && record != null && retainedCount <= 0) payloadError()
            val discarded = if (!retained || evicted) size.toLong() + prior.toLong() else 0L
            verifyPayloadArithmetic(size, prior, discarded, retained && !evicted)
        }

        private fun checkPayloadCapacity(row: HotSpotWireRecord) {
            if (row.kind != "event" && row.kind != "enrichment" && row.kind != "recovery") return
            val size = row.byteSize
            validateLedgerState(size)
            if (row.kind == "recovery") {
                val discarded = if (size > limits.maxRetainedBytes - bytes) size.toLong() else 0L
                verifyPayloadArithmetic(size, 0, discarded, discarded == 0L)
                return
            }
            checkOccurrencePayload(tracked[row.eventId], size)
        }

        private fun checkedRetainedAdd(
            current: Int,
            size: Int,
        ): Int {
            if (current < 0 || size < 0) payloadError()
            return try {
                Math.addExact(current, size)
            } catch (_: ArithmeticException) {
                payloadError()
            }
        }

        // Event, enrichment and recovery line byte sizes are supplied by the wire reader.
        private fun accountPayload(
            size: Int,
            discarded: Long,
        ) {
            if (size < 0 || discarded < 0 || admittedPayloadBytes < 0 || discardedPayloadBytes < 0) payloadError()
            val admitted: Long
            val dropped: Long
            try {
                admitted = Math.addExact(admittedPayloadBytes, size.toLong())
                dropped = Math.addExact(discardedPayloadBytes, discarded)
            } catch (_: ArithmeticException) {
                payloadError()
            }
            admittedPayloadBytes = admitted
            discardedPayloadBytes = dropped
        }

        private fun charge(
            record: Tracked,
            size: Int,
        ) {
            if (size < 0 || bytes < 0 || record.bytes < 0 || record.bytes > bytes) payloadError()
            if (!record.retained) {
                accountPayload(size, size.toLong())
                return
            }
            if (size > limits.maxRetainedBytes - bytes) {
                if (retainedCount <= 0) payloadError()
                accountPayload(size, size.toLong() + record.bytes.toLong())
                bytes -= record.bytes
                record.bytes = 0
                record.retained = false
                record.event = null
                record.evidence = null
                retainedCount--
            } else {
                val nextBytes = checkedRetainedAdd(bytes, size)
                val nextRecordBytes = checkedRetainedAdd(record.bytes, size)
                accountPayload(size, 0L)
                bytes = nextBytes
                record.bytes = nextRecordBytes
            }
        }

        private fun acceptEvent(row: HotSpotWireRecord) {
            val record = occurrence(row)
            checkHotSpotProtocol(!record.seenEvent, "duplicate occurrence event")
            val event = mapper.event(row)
            if (expectation is HotSpotCaptureExpectation.Observed) {
                HotSpotObservedWire.event(row, expectation.target)
                record.cpu = row.objectValue.objectField("event").uint("cpu")
                record.correlationId = event.correlationId
            }
            val identity = event.pid.value to event.processStartTicks
            checkHotSpotProtocol(target == null || target == identity, "target/process generation changed")
            target = identity
            record.seenEvent = true
            eventOrder.add(row.eventId ?: throw HotSpotProtocolException("event lacks occurrence identity"))
            charge(record, row.byteSize)
            if (record.retained) record.event = event
        }

        private fun acceptTerminal(row: HotSpotWireRecord) {
            val record = occurrence(row)
            if (record.seenTerminal) throw HotSpotProtocolException("duplicate occurrence terminal")
            val evidence = mapper.evidence(row)
            if (expectation is HotSpotCaptureExpectation.Observed) {
                checkHotSpotProtocol(record.seenEvent, "observed terminal preceded event")
                checkHotSpotProtocol(evidence.attribution != "event_correlated" || record.correlationId > 0uL, "resolved evidence lacks correlation")
            }
            when (evidence.attribution) {
                "event_correlated" -> resolved++
                "unavailable" -> unavailable++
                "skipped" -> skipped++
                else -> throw HotSpotProtocolException("unsupported terminal attribution")
            }
            record.seenTerminal = true
            charge(record, row.byteSize)
            if (record.retained) record.evidence = evidence
        }

        fun finish(receipt: HotSpotWireReceipt? = null): StructuredProfileEvidence {
            checkPayloadFailure()
            val final = summary ?: throw HotSpotProtocolException("EOF without final summary")
            checkHotSpotProtocol(tracked.values.all { it.seenEvent && it.seenTerminal }, "unpaired event/terminal at final summary")
            if (final.received != eventOrder.size.toULong() ||
                final.resolved != resolved ||
                final.unavailable != unavailable ||
                final.skipped != skipped
            ) {
                    throw HotSpotProtocolException("summary terminal counters do not match delivered records")
                }
            if (expectation is HotSpotCaptureExpectation.Observed) validateObservedFinal(final)
            return build(final, true, receipt)
        }

        private fun acceptRecovery(row: HotSpotWireRecord) {
            val record = tracked[row.eventId] ?: throw HotSpotProtocolException("orphan recovery")
            val value = row.objectValue.objectField("recovery")
            checkHotSpotProtocol(
                record.seenTerminal &&
                    !record.recovered &&
                    record.correlationId > 0uL &&
                    value.ulong("correlation_id") == record.correlationId &&
                    value.ulong("recovery_boot_ns") > 0uL,
                "invalid recovery reference",
            )
            record.recovered = true
            if (value.boolean("invalidated")) recoveryInvalidations++
            if (!value.optionalText("error").isNullOrEmpty()) recoveryErrors++
        }

        private fun validateObservedFinal(final: HotSpotReplaySummary) {
            val value = Json.parseToJsonElement(final.rawJson) as JsonObject
            val stats = value.objectField("enrichment")
            checkHotSpotProtocol(
                stats.ulong("recovery_attempts") == recoveryCount.toULong() &&
                    stats.ulong("recovery_invalidations") == recoveryInvalidations &&
                    stats.ulong("recovery_errors") == recoveryErrors,
                "summary recovery counts mismatch",
            )
            val possible = value.objectField("selected_event_accounting").uint("possible_cpus")
            checkHotSpotProtocol(tracked.values.all { it.cpu < possible }, "event CPU outside possible cardinality")
        }

        fun snapshot(): StructuredProfileEvidence {
            checkPayloadFailure()
            val observed = summary ?: HotSpotReplaySummary(
                eventOrder.size.toULong(),
                resolved,
                unavailable,
                skipped,
                false,
                false,
                null,
                "{\"kind\":\"partial_snapshot\",\"summary\":{\"terminal_accounting_complete\":false}}",
            )
            return build(observed.copy(terminalAccountingComplete = false), false)
        }

        private fun build(
            final: HotSpotReplaySummary,
            finalized: Boolean,
            wireReceipt: HotSpotWireReceipt? = null,
        ): StructuredProfileEvidence {
            val records = eventOrder.mapNotNull { id ->
                val record = tracked.getValue(id)
                if (record.retained && record.seenEvent && record.seenTerminal) HotSpotOccurrence(id, checkNotNull(record.event), checkNotNull(record.evidence)) else null
            }
            val discardedOccurrences = tracked.values.count { !it.retained }.toULong()
            var reconstructedBytes = 0
            for (record in tracked.values) {
                if (record.retained && record.seenEvent && record.seenTerminal) {
                    reconstructedBytes = Math.addExact(reconstructedBytes, record.bytes)
                }
            }
            for (recoveryJson in recoveries) {
                val recBytes = recoveryJson.toByteArray(Charsets.UTF_8).size
                reconstructedBytes = Math.addExact(reconstructedBytes, recBytes)
            }
            if (finalized) {
                checkHotSpotProtocol(
                    reconstructedBytes == bytes,
                    "running bytes ledger ($bytes) does not match reconstructed retained charge ($reconstructedBytes)",
                )
                checkHotSpotProtocol(
                    reconstructedBytes <= limits.maxRetainedBytes,
                    "retained bytes ($reconstructedBytes) exceed budget (${limits.maxRetainedBytes})",
                )
            }
            val allPaired = tracked.values.all { it.seenEvent && it.seenTerminal }
            val counts = HotSpotAccumulatorCounts(
                admittedOccurrences = tracked.size.toULong(),
                retainedOccurrences = records.size.toULong(),
                discardedOccurrences = discardedOccurrences,
                admittedRecoveries = recoveryCount.toULong(),
                retainedRecoveries = recoveries.size.toULong(),
                discardedRecoveries = discardedRecoveries,
                retainedBytes = bytes,
                resolved = resolved,
                unavailable = unavailable,
                skipped = skipped,
                allPaired = allPaired,
                chargedBytes = reconstructedBytes,
                admittedPayloadBytes = admittedPayloadBytes,
                discardedPayloadBytes = discardedPayloadBytes,
            )
            val summaryJson = try {
                Json.parseToJsonElement(final.rawJson) as? JsonObject
            } catch (_: SerializationException) {
                null
            }
            val accountingOutcome = if (finalized) {
                HotSpotCollectionAccounting.evaluate(
                    summaryObject = summaryJson,
                    wireReceipt = wireReceipt,
                    counts = counts,
                    expectedSessionId = session.orEmpty(),
                    expectedPid = target?.first,
                    expectedStartTicks = target?.second,
                    physicalDrainComplete = final.physicalDrainComplete,
                    terminalAccountingComplete = final.terminalAccountingComplete,
                )
            } else {
                HotSpotCollectionOutcome.Unknown("snapshot unfinalized")
            }
            return StructuredProfileEvidence(
                sessionId = checkNotNull(session),
                records = records,
                summary = final,
                recoveries = recoveries,
                retention = HotSpotRetentionFacts(
                    discardedOccurrences = discardedOccurrences,
                    retainedBytes = bytes,
                    discardedRecoveries = discardedRecoveries,
                    finalized = finalized,
                    pendingOccurrences = tracked.values.count { !it.seenEvent || !it.seenTerminal }.toULong(),
                ),
                runtimeProfile = expectation?.runtimeProfile,
                collectionAccounting = accountingOutcome,
                collectionReceipt = HotSpotCollectionReceipt(wireReceipt, counts),
            )
        }
    }
}
