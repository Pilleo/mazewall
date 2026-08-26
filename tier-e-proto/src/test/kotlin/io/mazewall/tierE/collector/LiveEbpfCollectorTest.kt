package io.mazewall.tierE.collector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

internal class LiveEbpfCollectorTest {

    @Test
    fun `records attributed events`() {
        val c = LiveEbpfCollector()
        c.record(tid = 100, syscallNr = 39, contextId = 42u, ktimeNs = 1_000L)
        c.record(tid = 101, syscallNr = 257, contextId = 43u, ktimeNs = 2_000L)
        assertEquals(2, c.drain().size)
    }

    @Test
    fun `drain returns snapshot with correct fields`() {
        val c = LiveEbpfCollector()
        c.record(tid = 100, syscallNr = 39, contextId = 42u, ktimeNs = 12345L)
        val drained = c.drain()
        assertEquals(1, drained.size)
        assertEquals(100L, drained[0].tid)
        assertEquals(39, drained[0].syscallNr)
        assertEquals(42u, drained[0].contextId)
        assertEquals(12345L, drained[0].ktimeNs)
    }

    @Test
    fun `clear empties events`() {
        val c = LiveEbpfCollector()
        c.record(100, 39, 42u, 1_000L)
        c.clear()
        assertTrue(c.drain().isEmpty())
    }

    @Test
    fun `drop tracking works`() {
        val c = LiveEbpfCollector()
        assertEquals(0, c.droppedCount)
        assertTrue(c.drainComplete)
        c.recordDrop()
        assertEquals(1, c.droppedCount)
        assertFalse(c.drainComplete)
    }
}
