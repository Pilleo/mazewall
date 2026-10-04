package io.mazewall.profiler.hotspot

import io.mazewall.profiler.internal.hotspot.HotSpotEvidenceAssembler
import io.mazewall.profiler.internal.hotspot.HotSpotProtocolException
import io.mazewall.profiler.internal.hotspot.HotSpotReplayLimits
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
