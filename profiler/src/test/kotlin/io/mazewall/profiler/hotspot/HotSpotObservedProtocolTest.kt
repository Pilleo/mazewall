package io.mazewall.profiler.hotspot

import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.profiler.api.HotSpotObservationTarget
import io.mazewall.profiler.internal.hotspot.HotSpotCaptureExpectation
import io.mazewall.profiler.internal.hotspot.HotSpotCollectorFailure
import io.mazewall.profiler.internal.hotspot.HotSpotProtocol
import io.mazewall.profiler.internal.hotspot.HotSpotProtocolException
import io.mazewall.profiler.internal.hotspot.HotSpotReplayLimits
import io.mazewall.profiler.internal.hotspot.HotSpotSessionEvent
import io.mazewall.profiler.internal.hotspot.HotSpotWireReader
import io.mazewall.profiler.internal.hotspot.HotSpotWireRecord
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** Synthetic wire-contract checks, not native profiling or qualification evidence. */
@EnabledIfLinuxAndSupported
class HotSpotObservedProtocolTest {
    private val target = HotSpotObservationTarget(Pid(123), 456uL, 789uL, 42uL)

    @Test
    fun `definition reader diagnostics reject malformed known scalar types`() {
        val protocol = HotSpotProtocol(123, 456uL, HotSpotCaptureExpectation.Observed(target))
        for ((from, to) in listOf(
            "\"lost\":0" to "\"lost\":-1",
            "\"no_data\":0" to "\"no_data\":\"0\"",
            "\"read_error\":0" to "\"read_error\":true",
            "\"lost\":0" to "\"lost\":18446744073709551616",
            "\"buffer_bytes_per_cpu\":524288" to "\"buffer_bytes_per_cpu\":9223372036854775808",
            "\"semantics\":\"consumed_trace_perf_records_partial_snapshot\"" to "\"semantics\":0",
        )) {
            kotlin.test.assertTrue(from in session())
            assertFailsWith<HotSpotProtocolException>(to) {
                protocol.controlEvent(rows(session().replace(from, to)).last())
            }
        }
        kotlin.test.assertNull(protocol.controlEvent(rows(session().replace("\"lost\":0", "\"lost\":18446744073709551615")).last()))
    }

    @Test
    fun `required zero values cannot disappear at the observed boundary`() {
        val protocol = HotSpotProtocol(123, 456uL, HotSpotCaptureExpectation.Observed(target))
        for ((kind, member) in listOf(
            "event" to "\"flags\":0,",
            "event" to "\"mode\":0,",
            "event" to "\"dirfd\":-100,",
            "enrichment" to "\"function_offset\":0,",
            "session_summary" to "\"read_error\":0,",
        )) {
            assertFailsWith<HotSpotProtocolException>(member) {
                protocol.controlEvent(rows(session().replace(member, "")).single { it.kind == kind })
            }
        }
    }

    @Test
    fun `compound native permission causes remain typed and malformed causes are rejected`() {
        val protocol = HotSpotProtocol(123, 456uL, HotSpotCaptureExpectation.Observed(target))
        val started = rows(session()).first().rawJson
        val failure = started
            .replace("\"kind\":\"session_started\"", "\"kind\":\"collector_error\"")
            .replace("\"lifecycle\":{", "\"lifecycle\":{\"message\":\"original native failure\",\"errno\":5,\"errno_causes\":[5,13],")
        val control = assertIs<HotSpotSessionEvent.Failure>(protocol.controlEvent(rows("$failure\n").single()))
        val cause = assertIs<HotSpotCollectorFailure>(control.cause)
        assertEquals(listOf(5u, 13u), cause.nativeErrnos)
        assertEquals("original native failure", cause.message)
        assertFailsWith<UnsupportedOperationException> { (cause.nativeErrnos as MutableList).clear() }
        for (invalid in listOf("null", "[]", "[null]", "[5,5]", "[5,13,13]", "[5,1,13,2]", "[13]", "[5,2]", "[0]", "[4096]", "[5,1.5]", "[\"5\"]")) {
            assertFailsWith<HotSpotProtocolException>(invalid) {
                protocol.controlEvent(rows(failure.replace("[5,13]", invalid) + "\n").single())
            }
        }
        val misplaced = failure.replace("\"kind\":\"collector_error\"", "\"kind\":\"session_started\"")
        assertFailsWith<HotSpotProtocolException> { protocol.controlEvent(rows("$misplaced\n").single()) }
    }

    @Test
    fun `observed summary preserves unknown loss and bound selected accounting`() {
        val protocol = HotSpotProtocol(123, 456uL, HotSpotCaptureExpectation.Observed(target))
        kotlin.test.assertNull(protocol.controlEvent(rows(session()).last()))
        for ((from, to) in listOf(
            "\"loss_complete\":false" to "\"loss_complete\":true",
            "\"collection_health\":\"partial\"" to "\"collection_health\":\"complete\"",
            "\"unproven_collection_sources\":[\"definitions\",\"enrichment\",\"wire\",\"retention\"]" to "\"unproven_collection_sources\":[]",
            "\"attempted\":1" to "\"attempted\":0",
            "\"native_reserve_failures\":0" to "\"native_reserve_failures\":1",
            "\"possible_cpus\":1" to "\"possible_cpus\":0",
            "\"producers_quiesced\":true" to "\"producers_quiesced\":false",
        )) {
            assertFailsWith<HotSpotProtocolException>(from) {
                protocol.controlEvent(rows(session().replace(from, to)).last())
            }
        }
    }

    @Test
    fun `observed ready matches the supplied target and upstream validation mode`() {
        val protocol = HotSpotProtocol(123, 456uL, HotSpotCaptureExpectation.Observed(target))
        val ready = rows(session()).single { it.kind == "collector_ready" }
        assertIs<HotSpotSessionEvent.Ready>(protocol.controlEvent(ready))
        for ((from, to) in listOf(
            "\"pid\":123" to "\"pid\":124",
            "\"process_start_ticks\":456" to "\"process_start_ticks\":457",
            "\"cgroup_id\":789" to "\"cgroup_id\":790",
            "\"mount_namespace\":42" to "\"mount_namespace\":43",
            "upstream_generation_checks" to "safepoint_epoch",
            "otel_hotspot25_observed_v1" to "temurin25_xint_serial",
            "e4dad227" to "ffffffff",
            "7097db14" to "ffffffff",
        )) {
            val changed = rows(session().replace(from, to)).single { it.kind == "collector_ready" }
            assertFailsWith<HotSpotProtocolException>(from) { protocol.controlEvent(changed) }
        }
    }

    @Test
    fun `observed records cannot cross target cgroup or provider`() {
        val protocol = HotSpotProtocol(123, 456uL, HotSpotCaptureExpectation.Observed(target))
        val event = rows(session()).single { it.kind == "event" }
        kotlin.test.assertNull(protocol.controlEvent(event))
        val enrichment = rows(session()).single { it.kind == "enrichment" }
        kotlin.test.assertNull(protocol.controlEvent(enrichment))
        for ((from, to) in listOf(
            "\"cgroup_id\":789" to "\"cgroup_id\":790",
            "\"sequence\":1" to "\"sequence\":2",
            "\"correlation_id\":1" to "\"correlation_id\":18446744073709551615",
            "\"argument_read\":11" to "\"argument_read\":12",
        )) {
            assertFailsWith<HotSpotProtocolException>(from) {
                protocol.controlEvent(rows(session().replace(from, to)).single { it.kind == "event" })
            }
        }
        assertFailsWith<HotSpotProtocolException> {
            protocol.controlEvent(rows(session().replace("7097db14", "ffffffff")).single { it.kind == "enrichment" })
        }
    }

    @Test
    fun `explicit schema3 reader accepts observational session`() {
        assertEquals(
            listOf("session_started", "collector_ready", "event", "enrichment", "session_summary"),
            rows(session()).map { it.kind },
        )
    }

    @Test
    fun `schema3 requires explicit false policy eligibility on every row`() {
        for (bad in listOf("true", "null", "0", "\"false\"")) {
            assertFailsWith<HotSpotProtocolException> {
                rows(session().replace("\"policy_eligibility\":false", "\"policy_eligibility\":$bad"))
            }
        }
        assertFailsWith<HotSpotProtocolException> {
            rows(session().replace("\"policy_eligibility\":false,", ""))
        }
    }

    @Test
    fun `strict reader cannot silently consume observational fields`() {
        val strictSchema = session().replace("\"schema_version\":3", "\"schema_version\":2")
        assertFailsWith<HotSpotProtocolException> {
            HotSpotWireReader(HotSpotReplayLimits(), 2uL).read(strictSchema.byteInputStream()) {}
        }
    }

    private fun rows(text: String): List<HotSpotWireRecord> =
        buildList {
        HotSpotWireReader(HotSpotReplayLimits(), 3uL).read(text.byteInputStream()) { add(it) }
    }

    private fun session(): String =
        requireNotNull(
        javaClass.getResourceAsStream("/hotspot/observed-consumer.jsonl"),
    ).bufferedReader().use { it.readText() }
}
