package io.mazewall.profiler.hotspot

import io.mazewall.profiler.hotspot.performance.HotSpotWorkloadMeasurementSettings
import io.mazewall.profiler.hotspot.performance.measureReplay
import io.mazewall.profiler.hotspot.performance.measureReplayAndRender
import io.mazewall.profiler.internal.hotspot.HotSpotEvidenceAssembler
import io.mazewall.profiler.internal.hotspot.HotSpotEvidenceDocument
import io.mazewall.profiler.internal.hotspot.HotSpotProtocolException
import io.mazewall.profiler.internal.hotspot.HotSpotReplayLimits
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HotSpotPerformanceMeasurementTest {
    @Test
    fun `rendered measurement preserves exact validated diagnostics`() {
        val bytes = projection()
        val actual = measureReplayAndRender(bytes.inputStream(), HotSpotReplayLimits())
        val expected = HotSpotEvidenceDocument.encode(HotSpotEvidenceAssembler().read(bytes.inputStream()))
        assertEquals(expected, actual.canonicalJson)
        assertEquals(3, actual.replay.wireRecords)
        assertFalse(actual.replay.evidence.collectionLossComplete)
        assertTrue(actual.renderNanos >= 0)
    }

    @Test
    fun `diagnostic settings preserve accepted defaults and enforce bounds`() {
        assertEquals(HotSpotWorkloadMeasurementSettings(20, 40, 100), HotSpotWorkloadMeasurementSettings())
        assertFailsWith<IllegalArgumentException> { HotSpotWorkloadMeasurementSettings(operations = 601) }
        assertFailsWith<IllegalArgumentException> { HotSpotWorkloadMeasurementSettings(warmups = -1) }
        assertFailsWith<IllegalArgumentException> { HotSpotWorkloadMeasurementSettings(paceMillis = 0) }
    }

    @Test
    fun `measurement preserves paired identity and ordered frames`() {
        val bytes = projection()
        val baseline = HotSpotEvidenceAssembler().read(bytes.inputStream())
        val measured = measureReplay(bytes.inputStream(), HotSpotReplayLimits())
        assertEquals(3, measured.wireRecords)
        assertEquals(baseline.records.map { it.eventId }, measured.evidence.records.map { it.eventId })
        assertEquals(
            baseline.records
            .single()
            .evidence.frames
            .map { it.functionName },
                measured.evidence.records
                .single()
                .evidence.frames
                .map { it.functionName },
        )
        assertFalse(measured.evidence.collectionLossComplete)
        assertTrue(measured.pipelineNanos >= measured.mappingNanos)
    }

    @Test
    fun `measurement refuses a missing summary`() {
        val rows = projection()
            .toString(Charsets.UTF_8)
            .lineSequence()
            .filter { it.isNotEmpty() }
            .toList()
        val incomplete = rows.dropLast(1).joinToString("\n", postfix = "\n")
        assertFailsWith<HotSpotProtocolException> { measureReplay(incomplete.byteInputStream(), HotSpotReplayLimits()) }
    }

    private fun projection(): ByteArray = checkNotNull(javaClass.getResourceAsStream("/hotspot/measured-event-projection.jsonl")).use { it.readBytes() }
}
