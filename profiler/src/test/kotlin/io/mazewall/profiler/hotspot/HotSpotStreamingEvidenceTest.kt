package io.mazewall.profiler.hotspot

import io.mazewall.profiler.internal.hotspot.HotSpotEvidenceAssembler
import io.mazewall.profiler.internal.hotspot.HotSpotProtocol
import io.mazewall.profiler.internal.hotspot.HotSpotReplayLimits
import io.mazewall.profiler.internal.hotspot.HotSpotWireReader
import io.mazewall.profiler.internal.hotspot.HotSpotWireRecord
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HotSpotStreamingEvidenceTest {
    @Test
    fun `partial snapshots distinguish pending records from retention loss and remain immutable`() {
        val rows = rows()
        val accumulator = HotSpotEvidenceAssembler().begin()
        accumulator.accept(rows[0])
        val pending = accumulator.snapshot()
        assertFalse(pending.finalized)
        assertFalse(pending.terminalAccountingComplete)
        assertEquals(1uL, pending.pendingOccurrences)
        assertEquals(0uL, pending.discardedOccurrences)
        accumulator.accept(rows[1])
        val paired = accumulator.snapshot()
        assertEquals(1, paired.records.size)
        assertEquals(0uL, paired.pendingOccurrences)
        assertEquals(0, pending.records.size)
        accumulator.accept(rows[2])
        assertTrue(accumulator.finish().finalized)
        assertFalse(paired.finalized)
    }

    @Test
    fun `retention cap continues terminal accounting while final evidence reports loss of retained records`() {
        val accumulator = HotSpotEvidenceAssembler(HotSpotReplayLimits(maxRetainedOccurrences = 0)).begin()
        rows().forEach(accumulator::accept)
        val final = accumulator.finish()
        assertEquals(0, final.records.size)
        assertEquals(1uL, final.discardedOccurrences)
        assertTrue(final.terminalAccountingComplete)
        assertFalse(final.retentionComplete)
    }

    @Test
    fun `EOF cannot finalize pending or summaryless evidence`() {
        val accumulator = HotSpotEvidenceAssembler().begin()
        accumulator.accept(rows()[0])
        assertFailsWith<IllegalArgumentException> { accumulator.finish() }
    }

    @Test
    fun `final summary uses the assembler summary object rather than a wire envelope`() {
        val accumulator = HotSpotEvidenceAssembler().begin()
        rows().forEach(accumulator::accept)
        assertFalse(HotSpotProtocol(123, 456uL).summaryEvent(accumulator.finish()).healthy)
    }

    private fun rows(): List<HotSpotWireRecord> {
        val rows = mutableListOf<HotSpotWireRecord>()
        requireNotNull(javaClass.getResourceAsStream("/hotspot/measured-event-projection.jsonl")).use { input ->
            HotSpotWireReader(HotSpotReplayLimits()).read(input, rows::add)
        }
        return rows
    }
}
