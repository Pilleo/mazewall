package io.mazewall.profiler.hotspot

import io.mazewall.profiler.internal.hotspot.HotSpotEvidenceAssembler
import io.mazewall.profiler.internal.hotspot.HotSpotEvidenceDocument
import io.mazewall.profiler.internal.hotspot.HotSpotProtocolException
import io.mazewall.profiler.internal.hotspot.HotSpotReplayLimits
import io.mazewall.profiler.internal.hotspot.HotSpotWireReader
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HotSpotEvidenceAssemblerTest {
    @Test
    fun `genuine compact projection preserves provider quality and exact paired event`() {
        val input = checkNotNull(javaClass.getResourceAsStream("/hotspot/measured-event-projection.jsonl"))
        val snapshot = input.use { HotSpotEvidenceAssembler().read(it) }
        val record = snapshot.records.single()
        assertEquals("e600a9611e1c4b50a2929989785c1399:8:61", record.eventId)
        assertEquals(255512878481428uL, record.event.correlationId)
        assertEquals(listOf("void BenchmarkFixture.leaf(java.lang.String)", "void BenchmarkFixture.route0(java.lang.String)"), record.evidence.frames.map { it.functionName })
        assertEquals(
            5u,
            record.evidence.frames
            .first()
            .bci,
        )
        assertEquals("e908bb2689936481f8a696dd2347457cc44b6344c1fa78ca65b37772032703eb", record.evidence.provider?.patchSha256)
        assertFalse(snapshot.summary.physicalDrainComplete)
    }

    @Test
    fun `recovery diagnostics share bounded retention and cannot bypass a zero payload budget`() {
        val recovery = """{"schema_version":1,"kind":"recovery","session_id":"$SESSION","recovery":{"correlation_id":1,"invalidated":true}}"""
        val snapshot = replay(event(1), recovery, terminal(1), summary(1), limits = HotSpotReplayLimits(maxRetainedBytes = 0))
        assertTrue(snapshot.recoveries.isEmpty())
        assertFalse(snapshot.retentionComplete)
        assertEquals(0, snapshot.retainedBytes)
    }

    @Test
    fun `valid recovery with non-ASCII and whitespace preserves exact original raw line UTF-8 bytes charge`() {
        val ev = event(1)
        val tr = terminal(1)
        val recovery = """  {"schema_version": 1, "kind": "recovery", "session_id": "$SESSION", "note": "café / 🚀 whitespace", "recovery": {"correlation_id": 1, "invalidated": true}}  """
        val evBytes = ev.toByteArray(Charsets.UTF_8).size
        val trBytes = tr.toByteArray(Charsets.UTF_8).size
        val recBytes = recovery.toByteArray(Charsets.UTF_8).size
        assertTrue(recBytes > recovery.length, "UTF-8 byte size must differ from String char length for non-ASCII content")

        // 1. Adequate capacity: retain complete pair + recovery; assert exact event+terminal+recovery byte sum and zero discards
        val snapshotAdequate = replay(ev, recovery, tr, summary(1))
        assertEquals(1, snapshotAdequate.records.size)
        assertEquals(listOf(recovery), snapshotAdequate.recoveries)
        assertEquals(evBytes + trBytes + recBytes, snapshotAdequate.retainedBytes)
        assertEquals(0uL, snapshotAdequate.discardedOccurrences)
        assertEquals(0uL, snapshotAdequate.discardedRecoveries)
        assertTrue(snapshotAdequate.retentionComplete)

        // 2. Recovery capacity exhausted:
        // Set budget to evBytes + trBytes.
        // Pad recovery with whitespace to ensure recBytes > trBytes so remaining capacity is exhausted after event.
        val padding = " ".repeat(trBytes + 16)
        val paddedRecovery =
            """  {"schema_version": 1, "kind": "recovery", "session_id": "$SESSION", """ +
                """"note": "café / 🚀 whitespace$padding", "recovery": {"correlation_id": 1, "invalidated": true}}  """
        val paddedRecBytes = paddedRecovery.toByteArray(Charsets.UTF_8).size
        assertTrue(paddedRecBytes > trBytes, "Padded recovery bytes must exceed terminal bytes")

        val snapshotExhausted = replay(ev, paddedRecovery, tr, summary(1), limits = HotSpotReplayLimits(maxRetainedBytes = evBytes + trBytes))
        assertEquals(1, snapshotExhausted.records.size)
        assertTrue(snapshotExhausted.recoveries.isEmpty())
        assertEquals(evBytes + trBytes, snapshotExhausted.retainedBytes)
        assertEquals(0uL, snapshotExhausted.discardedOccurrences)
        assertEquals(1uL, snapshotExhausted.discardedRecoveries)
        assertFalse(snapshotExhausted.retentionComplete)
        assertTrue(snapshotExhausted.terminalAccountingComplete)
    }

    @Test
    fun `terminal arrival evicts previously retained event when pair exceeds budget`() {
        val ev = event(1)
        val tr = terminal(1)
        val sm = summary(1)
        val evBytes = ev.toByteArray(Charsets.UTF_8).size
        val trBytes = tr.toByteArray(Charsets.UTF_8).size
        val budget = evBytes + trBytes - 1
        assertTrue(evBytes <= budget, "Event alone must fit within budget")

        val limits = HotSpotReplayLimits(maxRetainedBytes = budget)
        val assembler = HotSpotEvidenceAssembler(limits)
        val accumulator = assembler.begin()
        val reader = HotSpotWireReader(limits)

        // 1. Deliver event alone through real reader and observe accumulator: positive retained bytes, zero discards
        val eventInput = ByteArrayInputStream((ev + "\n").toByteArray(Charsets.UTF_8))
        reader.read(eventInput, accumulator::accept)
        val partial = accumulator.snapshot()
        assertEquals(evBytes, partial.retainedBytes)
        assertEquals(0uL, partial.discardedOccurrences)
        assertTrue(partial.records.isEmpty(), "Unpaired event must not produce completed records")
        assertFalse(partial.finalized)

        // 2. Deliver terminal and summary through real reader
        val terminalInput = ByteArrayInputStream((tr + "\n" + sm + "\n").toByteArray(Charsets.UTF_8))
        val receipt = reader.read(terminalInput, accumulator::accept)
        val finalSnapshot = accumulator.finish(receipt)

        // Assert pair is evicted on terminal charge: kept bytes/records are zero, exactly one occurrence discarded
        assertEquals(0, finalSnapshot.retainedBytes)
        assertEquals(0, finalSnapshot.records.size)
        assertEquals(1uL, finalSnapshot.discardedOccurrences)
        assertFalse(finalSnapshot.retentionComplete)
        assertTrue(finalSnapshot.terminalAccountingComplete)
    }

    @Test
    fun `retained byte ledger maintains strict consistency between running bytes and reconstructed charges`() {
        // Under intact hard production limits (maxRetainedBytes <= 33,554,432, maxLineBytes <= 1,048,576, maxTracked <= 32,768),
        // any charge exceeding maxRetainedBytes is rejected by budget checks in charge() or acceptRecovery().
        // Consequently, running bytes never exceeds 32 MiB, and Math.addExact defensive overflow on Int in build()
        // cannot be reached with valid legal input.
        val ev1 = event(1)
        val tr1 = terminal(1)
        val ev2 = event(2)
        val tr2 = terminal(2)
        val snapshot = replay(ev1, tr1, ev2, tr2, summary(2))
        val ev1Bytes = ev1.toByteArray(Charsets.UTF_8).size
        val tr1Bytes = tr1.toByteArray(Charsets.UTF_8).size
        val ev2Bytes = ev2.toByteArray(Charsets.UTF_8).size
        val tr2Bytes = tr2.toByteArray(Charsets.UTF_8).size
        val expectedTotal = ev1Bytes + tr1Bytes + ev2Bytes + tr2Bytes
        assertEquals(expectedTotal, snapshot.retainedBytes)
        assertEquals(2, snapshot.records.size)
        assertEquals(0uL, snapshot.discardedOccurrences)
        assertTrue(snapshot.retentionComplete)
        assertTrue(snapshot.retainedBytes <= 33_554_432)
    }

    @Test
    fun `out of order terminal preserves event identity frame domains and unsigned values`() {
        val snapshot = replay(terminal(1), event(1), summary(1))
        val record = snapshot.records.single()
        assertEquals(18446744073709551615uL, record.event.correlationId)
        assertEquals(listOf("leaf", "caller"), record.evidence.frames.map { it.functionName })
        assertEquals(0u, record.evidence.frames[0].bci)
        assertEquals(null, record.evidence.frames[1].bci)
        assertEquals(42u, record.evidence.frames[0].sourceLine)
        assertEquals("observed_in_range", record.evidence.frames[0].bciState)
        assertEquals("returned", record.evidence.walk)
        assertEquals("mixed", record.evidence.scopeLookup)
        assertTrue(snapshot.finalized)
        assertFalse(snapshot.collectionLossComplete)
        assertTrue(snapshot.terminalAccountingComplete)
    }

    @Test
    fun `shared correlation reference never collapses separate occurrences`() {
        val snapshot = replay(event(1), event(2), terminal(2), terminal(1), summary(2))
        assertEquals(listOf("$SESSION:0:1", "$SESSION:0:2"), snapshot.records.map { it.eventId })
        assertEquals(2, snapshot.records.size)
    }

    @Test
    fun `snapshots expose immutable owned frame and record lists`() {
        val snapshot = replay(event(1), terminal(1), summary(1))
        assertFailsWith<UnsupportedOperationException> { (snapshot.records as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> {
            (
                snapshot.records
            .single()
            .evidence.frames as MutableList
            ).clear()
        }
    }

    @Test
    fun `duplicate terminal mismatched session or changed process generation abort replay`() {
        assertFailsWith<HotSpotProtocolException> { replay(event(1), terminal(1), terminal(1), summary(1)) }
        assertFailsWith<HotSpotProtocolException> { replay(event(1), terminal(1).replace(SESSION, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"), summary(1)) }
        assertFailsWith<HotSpotProtocolException> { replay(event(1), event(2).replace("\"process_start_ticks\":10", "\"process_start_ticks\":11"), terminal(1), terminal(2), summary(2)) }
    }

    @Test
    fun `missing or inconsistent terminal summary never certifies completion`() {
        assertFailsWith<HotSpotProtocolException> { replay(event(1), terminal(1)) }
        assertFailsWith<HotSpotProtocolException> { replay(event(1), terminal(1), summary(2)) }
        assertFailsWith<HotSpotProtocolException> { replay(event(1), summary(1)) }
    }

    @Test
    fun `retention count limit continues accounting without claiming retained completeness`() {
        val limits = HotSpotReplayLimits(maxRetainedOccurrences = 1)
        val snapshot = replay(event(1), event(2), terminal(2), terminal(1), summary(2), limits = limits)
        assertEquals(1, snapshot.records.size)
        assertEquals(1uL, snapshot.discardedOccurrences)
        assertFalse(snapshot.retentionComplete)
        assertTrue(snapshot.terminalAccountingComplete)
    }

    @Test
    fun `wire bounds unsupported schema oversized frames and truncated bytes are rejected`() {
        assertFailsWith<HotSpotProtocolException> { replay(event(1).replace("\"schema_version\":1", "\"schema_version\":99"), terminal(1), summary(1)) }
        assertFailsWith<HotSpotProtocolException> { replay(event(1), terminal(1), summary(1), limits = HotSpotReplayLimits(maxLineBytes = 64)) }
        assertFailsWith<HotSpotProtocolException> { replay(event(1), terminal(1), summary(1), limits = HotSpotReplayLimits(maxFramesPerEvidence = 1)) }
        assertFailsWith<HotSpotProtocolException> { replay(event(1), terminal(1).dropLast(3), summary(1)) }
    }

    @Test
    fun `permission diagnostics and unavailable frames remain unavailable`() {
        val unavailable = terminal(1)
            .replace("\"attribution\":\"event_correlated\"", "\"attribution\":\"unavailable\"")
            .replace("\"walk\":\"returned\"", "\"walk\":\"unknown\"")
            .replace("\"provider\":", "\"reason\":\"native_permission_failure\",\"raw_errors\":[\"EPERM\"],\"provider\":")
        val snapshot = replay(event(1), unavailable, summary(1).replace("\"resolved\":1,\"unavailable\":0", "\"resolved\":0,\"unavailable\":1"))
        assertEquals(
            "unavailable",
            snapshot.records
            .single()
            .evidence.attribution,
        )
        assertEquals(
            listOf("EPERM"),
            snapshot.records
            .single()
            .evidence.rawErrors,
        )
        assertEquals(
            "native_permission_failure",
            snapshot.records
            .single()
            .evidence.reason,
        )
    }

    @Test
    fun `truncated genuine stream cannot establish final wire delivery`() {
        val original = checkNotNull(
            javaClass.getResourceAsStream(
            "/hotspot/measured-event-projection.jsonl",
        ),
        ).use { it.readBytes() }
        val broken = original.copyOf(original.size / 2)
        assertTrue(broken.size < original.size)
        assertFailsWith<HotSpotProtocolException> {
            HotSpotEvidenceAssembler().read(ByteArrayInputStream(broken))
        }
    }

    @Test
    fun `actual wire bytes conserve immediately discarded and evicted payloads`() {
        val ev = event(1)
        val tr = terminal(1)
        val recovery = """  {"schema_version":1,"kind":"recovery","session_id":"$SESSION","note":"café 🚀","recovery":{"correlation_id":1,"invalidated":true}}  """
        val repeated = recovery.replace("café", "déjà")

        fun size(line: String) = line.toByteArray(Charsets.UTF_8).size.toLong()

        fun rows(vararg lines: String) =
            mutableListOf<io.mazewall.profiler.internal.hotspot.HotSpotWireRecord>().also { result ->
            HotSpotWireReader(HotSpotReplayLimits()).read(
                ByteArrayInputStream((lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)),
                result::add,
            )
        }

        fun field(
            acc: Any,
            name: String,
        ): Long =
            acc.javaClass.getDeclaredField(name).let {
            it.isAccessible = true
            it.getLong(acc)
        }

        fun checkLedger(
            limits: HotSpotReplayLimits,
            retained: Long,
            discarded: Long,
            vararg lines: String,
        ) {
            val acc = HotSpotEvidenceAssembler(limits).begin()
            rows(*lines, summary(1)).forEach(acc::accept)
            val final = acc.finish()
            val admitted = field(acc, "admittedPayloadBytes")
            val dropped = field(acc, "discardedPayloadBytes")
            assertEquals(lines.sumOf(::size), admitted)
            assertEquals(discarded, dropped)
            assertEquals(retained, final.retainedBytes.toLong())
            assertEquals(admitted, final.retainedBytes.toLong() + dropped)
        }
        val all = size(ev) + size(tr) + size(recovery) + size(repeated)
        checkLedger(HotSpotReplayLimits(maxRetainedBytes = 0), 0, all, ev, recovery, repeated, tr)
        checkLedger(
            HotSpotReplayLimits(maxRetainedOccurrences = 0),
            size(recovery) + size(repeated),
            size(ev) + size(tr),
            ev,
            recovery,
            repeated,
            tr,
        )
        checkLedger(HotSpotReplayLimits(maxRetainedBytes = size(ev).toInt()), 0, size(ev) + size(tr), ev, tr)
        checkLedger(HotSpotReplayLimits(maxRetainedBytes = size(ev).toInt()), 0, all, ev, recovery, repeated, tr)
        checkLedger(HotSpotReplayLimits(maxRetainedBytes = size(tr).toInt()), 0, size(ev) + size(tr), tr, ev)
        checkLedger(HotSpotReplayLimits(), all, 0, ev, recovery, repeated, tr)
    }

    @Test
    fun `byte counter overflow is sticky and does not admit the triggering row`() {
        val row = mutableListOf<io.mazewall.profiler.internal.hotspot.HotSpotWireRecord>()
        HotSpotWireReader(HotSpotReplayLimits()).read(
            ByteArrayInputStream((event(1) + "\n").toByteArray(Charsets.UTF_8)),
            row::add,
        )
        for (name in listOf("admittedPayloadBytes", "discardedPayloadBytes")) {
            val acc = HotSpotEvidenceAssembler(HotSpotReplayLimits(maxRetainedBytes = 0)).begin()
            val field = acc.javaClass.getDeclaredField(name).apply { isAccessible = true }
            field.setLong(acc, Long.MAX_VALUE)
            assertFailsWith<HotSpotProtocolException> { acc.accept(row.single()) }
            assertEquals(Long.MAX_VALUE, field.getLong(acc))
            assertFalse(acc.hasSession)
            assertFailsWith<HotSpotProtocolException> { acc.accept(row.single()) }
            assertFailsWith<HotSpotProtocolException> { acc.snapshot() }
            assertFailsWith<HotSpotProtocolException> { acc.finish() }
        }
    }

    @Test
    fun `actual detached receipts survive finalized zero retention and nonfinal snapshots`() {
        val ev = event(1)
        val tr = terminal(1)
        val recovery = """  {"schema_version":1,"kind":"recovery","session_id":"$SESSION","note":"café 🚀","recovery":{"correlation_id":1,"invalidated":true}}  """
        val input = listOf(ev, recovery, tr, summary(1)).joinToString("\n", postfix = "\n")
        val zero = HotSpotEvidenceAssembler(HotSpotReplayLimits(maxRetainedBytes = 0)).read(
            ByteArrayInputStream(input.toByteArray(Charsets.UTF_8)),
        )
        val receipt = checkNotNull(zero.collectionReceipt)
        val wire = checkNotNull(receipt.wireReceipt)
        val expectedPayload = listOf(ev, recovery, tr).sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }
        assertTrue(wire.validEof)
        assertEquals(3uL, wire.records)
        assertEquals(listOf(ev, recovery, tr).sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() + 1L }.toULong(), wire.bytes)
        val prefix = listOf(ev, recovery, tr).joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)
        val digest = java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(prefix)
            .joinToString("") { "%02x".format(it) }
        assertEquals(digest, wire.sha256)
        assertEquals(expectedPayload, receipt.counts.admittedPayloadBytes)
        assertEquals(expectedPayload, receipt.counts.discardedPayloadBytes)
        assertEquals(0, receipt.counts.retainedBytes)
        val roundTrip = HotSpotEvidenceDocument.decode(HotSpotEvidenceDocument.encode(zero))
        assertEquals(receipt, roundTrip.collectionReceipt)
        assertFalse(roundTrip.collectionLossComplete)
        assertTrue(roundTrip.collectionAccounting is io.mazewall.profiler.internal.hotspot.HotSpotCollectionOutcome.Unknown)

        val evicted = HotSpotEvidenceAssembler(HotSpotReplayLimits(maxRetainedBytes = ev.toByteArray(Charsets.UTF_8).size)).read(
            ByteArrayInputStream(input.toByteArray(Charsets.UTF_8)),
        )
        val evictionReceipt = checkNotNull(evicted.collectionReceipt)
        assertEquals(expectedPayload, evictionReceipt.counts.discardedPayloadBytes)
        assertEquals(0, evictionReceipt.counts.retainedBytes)
        assertEquals(evictionReceipt, HotSpotEvidenceDocument.decode(HotSpotEvidenceDocument.encode(evicted)).collectionReceipt)

        val rows = mutableListOf<io.mazewall.profiler.internal.hotspot.HotSpotWireRecord>()
        HotSpotWireReader(HotSpotReplayLimits()).read(ByteArrayInputStream(input.toByteArray(Charsets.UTF_8)), rows::add)
        val accumulator = HotSpotEvidenceAssembler().begin()
        accumulator.accept(rows.first())
        val pending = accumulator.snapshot()
        val frozen = checkNotNull(pending.collectionReceipt)
        assertEquals(null, frozen.wireReceipt)
        assertFalse(pending.finalized)
        assertEquals(1uL, pending.pendingOccurrences)
        assertEquals(frozen, HotSpotEvidenceDocument.decode(HotSpotEvidenceDocument.encode(pending)).collectionReceipt)
        accumulator.accept(rows[1])
        accumulator.accept(rows[2])
        assertEquals(frozen, pending.collectionReceipt)
        assertTrue(checkNotNull(accumulator.snapshot().collectionReceipt).counts.admittedPayloadBytes > frozen.counts.admittedPayloadBytes)
        accumulator.accept(rows[3])
        val noEof = accumulator.finish(
            io.mazewall.profiler.internal.hotspot
            .HotSpotWireReceipt(3uL, 10uL, "a".repeat(64), false),
        )
        val decoded = HotSpotEvidenceDocument.decode(HotSpotEvidenceDocument.encode(noEof))
        assertEquals(noEof.collectionReceipt, decoded.collectionReceipt)
        assertFalse(checkNotNull(checkNotNull(decoded.collectionReceipt).wireReceipt).validEof)
        assertFalse(decoded.collectionLossComplete)
    }

    companion object {
        const val SESSION = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        fun event(id: Int): String =
            """{"schema_version":1,"kind":"event","session_id":"$SESSION","event_id":"$SESSION:0:$id","event":{"session_id":"$SESSION","event_id":"$SESSION:0:$id","host_pid":123,"host_tid":124,"process_start_ticks":10,"capture_boot_ns":100,"clock_domain":"CLOCK_BOOTTIME","event_kind":"openat_handler_entry","correlation_id":18446744073709551615,"filename":"relative/path","path_truncated":false,"dirfd":-100,"flags":0}}"""

        fun terminal(id: Int): String =
            """{"schema_version":1,"kind":"enrichment","session_id":"$SESSION","event_id":"$SESSION:0:$id","evidence":{"attribution":"event_correlated","walk":"returned","scope_lookup":"mixed","provider":{"name":"otel-ebpf-profiler-via-gadget","gadget_revision":"g","profiler_revision":"p","patch_sha256":"sha"},"frames":[{"provider_type":5,"frame_type":"jvm","function_name":"leaf","source_line":42,"bci":0,"bci_state":"observed_in_range","scope_lookup":"interpreter","scope_walk":"unknown","raw_position":0,"position_domain":"provider_hotspot_bci_or_fallback","error_frame":false},{"provider_type":5,"frame_type":"jvm","function_name":"caller","raw_position":18446744073709551615,"position_domain":"provider_hotspot_bci_or_fallback","error_frame":false}]}}"""

        fun summary(count: Int): String =
            """{"schema_version":1,"kind":"session_summary","session_id":"$SESSION","summary":{"enrichment":{"received":$count,"resolved":$count,"unavailable":0,"skipped":0},"collection_health":"partial","terminal_accounting_complete":true,"event_loss_observation":{"observed":0,"complete":false},"definition_reader_observation":{"lost":0,"read_error":0,"complete":false},"definition_stop_observation":{"reader_stopped":true,"consumer_stopped":true,"buffers_drained":true,"auxiliary_stopped":true},"event_finalization_observation":{"producers_quiesced":true,"buffer_drained":true,"receiver_joined":true,"final_counter_read":true,"resources_retired":true,"loss_complete":false}}}"""

        internal fun replay(
            vararg records: String,
            limits: HotSpotReplayLimits = HotSpotReplayLimits(),
        ) = HotSpotEvidenceAssembler(limits).read(ByteArrayInputStream((records.joinToString("\n") + "\n").toByteArray()))
    }
}
