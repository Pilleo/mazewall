package io.mazewall.profiler.tierE.collector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LiveEbpfCollectorTest {
    @Test
    fun `snapshot atomically includes events and drop completeness`() {
        val collector = LiveEbpfCollector()
        collector.record(1, 2, 3u, 4)
        collector.recordDrop()

        val snapshot = collector.snapshot()

        assertEquals(1, snapshot.events.size)
        assertEquals(1, snapshot.droppedCount)
        assertFalse(snapshot.drainComplete)
    }
}
