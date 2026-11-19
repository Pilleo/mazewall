package io.mazewall.profiler.hotspot

import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.profiler.api.HotSpotObservationTarget
import io.mazewall.profiler.api.IncompleteProfileException
import io.mazewall.profiler.internal.hotspot.HotSpotCaptureExpectation
import io.mazewall.profiler.internal.hotspot.HotSpotCoverageAdapter
import io.mazewall.profiler.internal.hotspot.HotSpotEvidenceAssembler
import io.mazewall.profiler.internal.hotspot.HotSpotProtocol
import io.mazewall.profiler.internal.hotspot.HotSpotProtocolException
import io.mazewall.profiler.internal.hotspot.HotSpotReplayLimits
import io.mazewall.profiler.internal.hotspot.HotSpotWireReader
import io.mazewall.profiler.internal.hotspot.StructuredProfileEvidence
import io.mazewall.profiler.internal.hotspot.optionalText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@EnabledIfLinuxAndSupported
class HotSpotObservedConsumerTest {
    @Test
    fun `unavailable JIT metadata stays unavailable without invented frames`() {
        val text = session()
        val frameJson = Json
            .parseToJsonElement(text.lines()[3])
            .jsonObject
            .getValue("evidence")
            .jsonObject
            .getValue("frames")
            .toString()
        val unavailable = text
            .replace(frameJson, "[]")
            .replace("\"attribution\":\"event_correlated\"", "\"attribution\":\"unavailable\",\"reason\":\"JIT info evicted since eBPF snapshot\"")
            .replace("\"resolved\":1,\"unavailable\":0", "\"resolved\":0,\"unavailable\":1")
        val record = replay(unavailable).records.single()
        assertEquals("unavailable", record.evidence.attribution)
        assertEquals("JIT info evicted since eBPF snapshot", record.evidence.reason)
        assertTrue(record.evidence.frames.isEmpty())
    }

    @Test
    fun `discarded payload cannot bypass observed event accounting`() {
        val limits = HotSpotReplayLimits(maxRetainedOccurrences = 0)
        val evidence = replay(session(), limits)
        assertTrue(evidence.records.isEmpty())
        assertEquals(1uL, evidence.discardedOccurrences)
        assertEquals(1uL, evidence.summary.received)
        assertFalse(evidence.retentionComplete)
        assertFailsWith<HotSpotProtocolException> {
            replay(session().replace("observed:0:1", "observed:1:1").replace("\"cpu\":0", "\"cpu\":1"), limits)
        }
    }

    @Test
    fun `diagnostic report preserves observed provenance and refuses policy promotion`() {
        val public = io.mazewall.profiler.api
            .StructuredProfileEvidence(replay(session()))
        val json = public.toJson()
        assertEquals("otel_hotspot25_observed_v1", Json.parseToJsonElement(json).jsonObject.optionalText("runtimeProfile"))
        val restored = io.mazewall.profiler.api.StructuredProfileEvidence
            .fromJson(json)
        assertEquals(json, restored.toJson())
        assertFailsWith<HotSpotProtocolException> {
            io.mazewall.profiler.api.StructuredProfileEvidence
                .fromJson(json.replace("\"policyEligibility\":false", "\"policyEligibility\":true"))
        }
    }

    @Test
    fun `ordinary JVM frames preserve order precision and incomplete policy coverage`() {
        val evidence = replay(session())
        val result = HotSpotCoverageAdapter.toLiveResult(evidence)
        assertFalse(result.coverage.complete)
        assertFailsWith<IncompleteProfileException> { result.toDedicatedWorkerPolicies() }
        val frames = requireNotNull(result.structuredEvidence)
            .records
            .single()
            .evidence.frames
        assertEquals(listOf("leaf", "caller"), frames.map { it.functionName })
        assertEquals(9007199254740993uL, frames.first().rawPosition)
        assertEquals(ULong.MAX_VALUE, frames.last().rawPosition)
        assertEquals(0u, frames.first().bci)
        assertNull(frames.last().bci)
    }

    @Test
    fun `observed terminal cannot precede its original event`() {
        val lines = session().lines().filter { it.isNotEmpty() }.toMutableList()
        java.util.Collections.swap(lines, 2, 3)
        assertFailsWith<HotSpotProtocolException> { replay(lines.joinToString("\n", postfix = "\n")) }
    }

    @Test
    fun `event cpu cannot escape final possible CPU cardinality`() {
        val outside = session().replace("observed:0:1", "observed:1:1").replace("\"cpu\":0", "\"cpu\":1")
        assertFailsWith<HotSpotProtocolException> { replay(outside) }
    }

    @Test
    fun `recovery must reference a terminal once with its original correlation`() {
        val recovery = """{"schema_version":3,"policy_eligibility":false,"kind":"recovery","session_id":"observed","event_id":"observed:0:1",""" +
            """"recovery":{"correlation_id":1,"recovery_boot_ns":103,"invalidated":true}}"""
        val base = session().replace("\"recovery_attempts\":0", "\"recovery_attempts\":1").replace("\"recovery_invalidations\":0", "\"recovery_invalidations\":1")
        val valid = base
            .lines()
            .filter { it.isNotEmpty() }
            .toMutableList()
            .apply { add(4, recovery) }
            .joinToString("\n", postfix = "\n")
        assertEquals(1, replay(valid).recoveries.size)
        assertFailsWith<HotSpotProtocolException> { replay(valid.replace("\"correlation_id\":1,\"recovery_boot_ns\"", "\"correlation_id\":2,\"recovery_boot_ns\"")) }
        assertFailsWith<HotSpotProtocolException> { replay(valid.replace(recovery, "$recovery\n$recovery")) }
        assertFailsWith<HotSpotProtocolException> { replay(valid.replace("\"recovery_attempts\":1", "\"recovery_attempts\":0")) }
    }

    private fun replay(
        text: String,
        limits: HotSpotReplayLimits = HotSpotReplayLimits(),
    ): StructuredProfileEvidence {
        val expected = HotSpotCaptureExpectation.Observed(HotSpotObservationTarget(Pid(123), 456uL, 789uL, 42uL))
        val protocol = HotSpotProtocol(123, 456uL, expected)
        val accumulator = HotSpotEvidenceAssembler(limits, expected).begin()
        HotSpotWireReader(limits, 3uL).read(text.byteInputStream()) { row ->
            protocol.controlEvent(row)
            when (row.kind) {
                "session_started" -> accumulator.accept(row.copy(kind = "collector_attached"))
                "collector_ready" -> Unit
                else -> accumulator.accept(row)
            }
        }
        return accumulator.finish().also { protocol.summaryEvent(it) }
    }

    private fun session(): String = requireNotNull(javaClass.getResourceAsStream("/hotspot/observed-consumer.jsonl")).bufferedReader().use { it.readText() }
}
