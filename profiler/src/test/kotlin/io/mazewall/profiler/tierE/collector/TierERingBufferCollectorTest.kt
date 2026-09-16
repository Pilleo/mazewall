package io.mazewall.profiler.tierE.collector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class TierERingBufferCollectorTest {
    @Test
    fun `snapshot is immutable`() {
        val collector = TierERingBufferCollector()
        collector.record(1, 2, 3u, 4)
        assertFailsWith<UnsupportedOperationException> {
            (collector.snapshot().events as MutableList).clear()
        }
    }

    @Test
    fun `snapshot atomically includes events and drop completeness`() {
        val collector = TierERingBufferCollector()
        collector.record(1, 2, 3u, 4)
        collector.recordDrop()

        val snapshot = collector.snapshot()

        assertEquals(1, snapshot.events.size)
        assertEquals(1, snapshot.droppedCount)
        assertFalse(snapshot.drainComplete)
    }
}
