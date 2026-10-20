package io.mazewall.profiler.hotspot

import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.profiler.api.StructuredProfileEvidence
import io.mazewall.profiler.internal.hotspot.HotSpotCoverageAdapter
import io.mazewall.profiler.internal.hotspot.HotSpotEvidenceAssembler
import io.mazewall.profiler.internal.hotspot.HotSpotRawJson
import io.mazewall.profiler.internal.hotspot.HotSpotReplayLimits
import io.mazewall.profiler.internal.hotspot.HotSpotStackEvidence
import io.mazewall.profiler.internal.hotspot.HotSpotWireReader
import io.mazewall.profiler.internal.hotspot.HotSpotWireRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import io.mazewall.profiler.internal.hotspot.StructuredProfileEvidence as InternalEvidence

@EnabledIfLinuxAndSupported
class HotSpotPublicRawJsonTest {
    @Test
    fun `public partial construction and typed access preserve an unrendered immutable snapshot`() {
        val fixture = Fixture()
        val result = HotSpotCoverageAdapter.toLiveResult(fixture.owner)
        val public = requireNotNull(result.structuredEvidence)
        val occurrence = public.records.single()
        val stack = occurrence.evidence
        val frame = stack.frames.first()

        assertEquals(listOf(1, 0, 0, 0), fixture.renders.toList())
        assertSame(public, result.copy().structuredEvidence)
        assertFalse(public.finalized || public.terminalAccountingComplete || public.collectionLossComplete)
        assertEquals("event_correlated", stack.attribution)
        assertEquals("otel-ebpf-profiler-via-gadget", stack.provider?.name)
        assertEquals(listOf("λ猫.leaf", "void BenchmarkFixture.route0(java.lang.String)"), stack.frames.map { it.functionName })
        assertEquals(ULong.MAX_VALUE, frame.rawPosition)
        assertEquals(ULong.MAX_VALUE, frame.nativeAddress)
        assertEquals(UInt.MAX_VALUE, frame.rawBci)
        assertEquals(5u, frame.bci)
        assertEquals("provider_hotspot_bci_or_fallback", frame.positionDomain)
        assertEquals(listOf("EPERM λ猫"), stack.rawErrors)
        assertEquals(listOf("retained recovery"), public.recoveries)
        assertEquals(-100, occurrence.event.dirfd)
        val bytes = requireNotNull(occurrence.event.filenameBytes)
        bytes.fill(0)
        assertContentEquals(fixture.filenameBytes, occurrence.event.filenameBytes)
        assertFailsWith<UnsupportedOperationException> { (public.records as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (stack.frames as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (stack.rawErrors as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (public.recoveries as MutableList).clear() }
        assertEquals(listOf(1, 0, 0, 0), fixture.renders.toList())
    }

    @Test
    fun `public report renders aggregate diagnostics without individual frame strings`() {
        val fixture = Fixture()
        val public = StructuredProfileEvidence(fixture.owner)
        val report = public.toJson()

        assertEquals(listOf(1, 1, 0, 0), fixture.renders.toList())
        val encodedRecords = Json
            .parseToJsonElement(report)
            .jsonObject
            .getValue("records")
            .jsonArray
        val encodedStack = encodedRecords.single().jsonObject.getValue("evidence")
        assertContentEquals(fixture.stackJson.toByteArray(), encodedStack.toString().toByteArray())
        assertEquals(report, StructuredProfileEvidence.fromJson(report).toJson())
        fixture.assertRawGetters(public)
        assertEquals(listOf(1, 1, 1, 1), fixture.renders.toList())
        assertEquals(report, public.toJson())
    }

    @Test
    fun `requested raw getters reuse owned canonical strings without rendering siblings`() {
        val fixture = Fixture()
        val public = StructuredProfileEvidence(fixture.owner)
        val stack = public.records.single().evidence

        assertEquals(fixture.frameJson.first(), stack.frames.first().rawJson)
        assertEquals(listOf(1, 0, 1, 0), fixture.renders.toList())
        assertEquals(fixture.stackJson, stack.rawJson)
        assertEquals(listOf(1, 1, 1, 0), fixture.renders.toList())
        fixture.assertRawGetters(public)
        assertEquals(listOf(1, 1, 1, 1), fixture.renders.toList())
    }

    @Test
    fun `public event arguments are still eagerly rendered and validated`() {
        for (invalid in listOf("\"flags\":\"invalid\"", "\"filename_bytes\":\"!\"")) {
            val fixture = Fixture(invalid)
            assertFailsWith<IllegalArgumentException> { StructuredProfileEvidence(fixture.owner) }
            assertEquals(listOf(1, 0, 0, 0), fixture.renders.toList())
        }
    }

    private class Fixture(
        invalidArgument: String? = null,
    ) {
        val renders = IntArray(4)
        val owner: InternalEvidence
        val stackJson: String
        val frameJson: List<String>
        val filenameBytes: ByteArray

        init {
            val projection = requireNotNull(javaClass.getResourceAsStream("/hotspot/measured-event-projection.jsonl"))
                .bufferedReader()
                .use { it.readText() }
                .replace("\"raw_position\":5", "\"raw_position\":18446744073709551615,\"native_address\":18446744073709551615")
                .replace("\"raw_bci\":5", "\"raw_bci\":4294967295")
                .replace("void BenchmarkFixture.leaf(java.lang.String)", "λ猫.leaf")
                .replace("\"frames\":[", "\"unknown_provider\":{\"utf8\":\"λ猫\",\"unsigned\":18446744073709551615},\"raw_errors\":[\"EPERM λ猫\"],\"frames\":[")
                .replace("\"provider_type\":5", "\"unknown_frame\":\"λ猫\\\\quoted\",\"provider_type\":5")
            val rows = mutableListOf<HotSpotWireRecord>()
            HotSpotWireReader(HotSpotReplayLimits()).read(projection.byteInputStream(), rows::add)
            val accumulator = HotSpotEvidenceAssembler().begin()
            rows.take(2).forEach(accumulator::accept)
            val partial = accumulator.snapshot()
            val record = partial.records.single()
            val stack = record.evidence
            val tree = rows[1].objectValue.getValue("evidence").jsonObject
            stackJson = tree.toString()
            frameJson = tree.getValue("frames").jsonArray.map { it.toString() }
            val frames = stack.frames.mapIndexed { index, frame -> frame.copy(rawValue = counted(frameJson[index], index + 2)) }.toMutableList()
            val errors = stack.rawErrors.toMutableList()
            val evidence = HotSpotStackEvidence(stack.attribution, stack.walk, stack.scopeLookup, stack.reason, stack.provider, frames, errors, counted(stackJson, 1))
            var eventJson = rows[0].objectValue.getValue("event").toString()
            val eventTree = rows[0].objectValue.getValue("event").jsonObject
            val encodedFilename = eventTree.getValue("filename_bytes") as JsonPrimitive
            filenameBytes = Base64.getDecoder().decode(encodedFilename.content)
            if (invalidArgument != null) {
                eventJson = JsonObject(Json.parseToJsonElement(eventJson).jsonObject + Json.parseToJsonElement("{$invalidArgument}").jsonObject).toString()
            }
            val records = mutableListOf(record.copy(event = record.event.copy(rawValue = counted(eventJson, 0)), evidence = evidence))
            val recoveries = mutableListOf("retained recovery")
            owner = InternalEvidence(
                partial.sessionId,
                records,
                partial.summary,
                partial.discardedOccurrences,
                partial.retainedBytes,
                recoveries,
                partial.discardedRecoveries,
                partial.finalized,
                partial.pendingOccurrences,
            )
            frames.clear()
            errors.clear()
            records.clear()
            recoveries.clear()
        }

        fun assertRawGetters(public: StructuredProfileEvidence) {
            val stack = public.records.single().evidence
            val internalStack = owner.records.single().evidence
            assertContentEquals(stackJson.toByteArray(), stack.rawJson.toByteArray())
            assertSame(internalStack.rawJson, stack.rawJson)
            stack.frames.forEachIndexed { index, frame ->
                assertContentEquals(frameJson[index].toByteArray(), frame.rawJson.toByteArray())
                assertSame(internalStack.frames[index].rawJson, frame.rawJson)
                assertSame(frame.rawJson, frame.rawJson)
            }
        }

        private fun counted(
            text: String,
            index: Int,
        ): HotSpotRawJson =
            HotSpotRawJson(Json.parseToJsonElement(text)) {
                renders[index]++
                it.toString()
            }
    }
}
