package io.mazewall.profiler.hotspot

import io.mazewall.profiler.hotspot.performance.measureReplay
import io.mazewall.profiler.internal.hotspot.HotSpotEvidenceAssembler
import io.mazewall.profiler.internal.hotspot.HotSpotProtocolException
import io.mazewall.profiler.internal.hotspot.HotSpotReplayLimits
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HotSpotPerformanceMeasurementTest {
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
