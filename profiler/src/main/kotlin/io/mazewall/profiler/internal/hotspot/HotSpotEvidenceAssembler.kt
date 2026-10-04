package io.mazewall.profiler.internal.hotspot

import java.io.InputStream

internal class HotSpotEvidenceAssembler(
    private val limits: HotSpotReplayLimits = HotSpotReplayLimits(),
) {
    fun read(input: InputStream): StructuredProfileEvidence {
        val replay = Replay()
        HotSpotWireReader(limits).read(input, replay::accept)
        return replay.finish()
    }

    private class Tracked(
        val retain: Boolean,
    ) {
        var event: HotSpotEvent? = null
        var evidence: HotSpotStackEvidence? = null
        var seenEvent = false
        var seenTerminal = false
        var bytes = 0
        var retained = retain
    }

    private inner class Replay {
        private var session: String? = null
        private var target: Pair<Int, ULong>? = null
        private val tracked = linkedMapOf<String, Tracked>()
        private val eventOrder = mutableListOf<String>()
        private val recoveries = mutableListOf<String>()
        private var recoveryCount = 0
        private var discardedRecoveries = 0uL
        private var summary: HotSpotReplaySummary? = null
        private var retainedCount = 0
        private var bytes = 0
        private var resolved = 0uL
        private var unavailable = 0uL
        private var skipped = 0uL
        private val mapper = HotSpotWireMapper(limits)

        fun accept(row: HotSpotWireRecord) {
            checkHotSpotProtocol(summary == null, "record after terminal summary")
            if (session == null) session = row.sessionId
            checkHotSpotProtocol(session == row.sessionId, "collector session changed")
            when (row.kind) {
                "collector_attached" -> Unit
                "event" -> acceptEvent(row)
                "enrichment" -> acceptTerminal(row)
                "recovery" -> {
                    if (++recoveryCount > limits.maxTrackedOccurrences) throw HotSpotProtocolException("recovery record limit exceeded")
                    row.objectValue.objectField("recovery")
                    if (row.byteSize > limits.maxRetainedBytes - bytes) {
                        discardedRecoveries++
                    } else {
                        recoveries.add(row.rawJson)
                        bytes += row.byteSize
                    }
                }
                "session_summary" -> summary = mapper.summary(row)
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

        private fun charge(
            record: Tracked,
            size: Int,
        ) {
            if (!record.retained) return
            if (size > limits.maxRetainedBytes - bytes) {
                bytes -= record.bytes
                record.bytes = 0
                record.retained = false
                record.event = null
                record.evidence = null
                retainedCount--
            } else {
                bytes += size
                record.bytes += size
            }
        }

        private fun acceptEvent(row: HotSpotWireRecord) {
            val record = occurrence(row)
            checkHotSpotProtocol(!record.seenEvent, "duplicate occurrence event")
            val event = mapper.event(row)
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

        fun finish(): StructuredProfileEvidence {
            val final = summary ?: throw HotSpotProtocolException("EOF without final summary")
            checkHotSpotProtocol(tracked.values.all { it.seenEvent && it.seenTerminal }, "unpaired event/terminal at final summary")
            if (final.received != eventOrder.size.toULong() ||
                final.resolved != resolved ||
                final.unavailable != unavailable ||
                final.skipped != skipped
            ) {
                    throw HotSpotProtocolException("summary terminal counters do not match delivered records")
                }
            val records = eventOrder.mapNotNull { id ->
                val record = tracked.getValue(id)
                if (record.retained) HotSpotOccurrence(id, checkNotNull(record.event), checkNotNull(record.evidence)) else null
            }
            return StructuredProfileEvidence(checkNotNull(session), records, final, (tracked.size - records.size).toULong(), bytes, recoveries, discardedRecoveries)
        }
    }
}
