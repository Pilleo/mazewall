package io.mazewall.profiler.internal.attribution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecoveredEdgeLedgerTest {
    private val edge = UniqueEdgeGate.Edge(StackTraceId(7), 257, 0)
    private val other = UniqueEdgeGate.Edge(StackTraceId(9), 257, 0)

    @Test
    fun `acknowledged coverage commits only through the exact token`() {
        val ledger = RecoveredEdgeLedger()
        val token = assertNotNull(ledger.propose(edge))
        assertTrue(!ledger.contains(edge))

        ledger.acknowledge(token)
        assertTrue(ledger.contains(edge))
        assertNull(ledger.propose(edge))
    }

    @Test
    fun `failed first retention stays eligible for retry`() {
        val ledger = RecoveredEdgeLedger()
        val first = assertNotNull(ledger.propose(edge))
        val retry = assertNotNull(ledger.propose(edge))
        assertEquals(first, retry)

        ledger.acknowledge(retry)
        assertTrue(ledger.contains(edge))
    }

    @Test
    fun `duplicate stale foreign tokens cannot add coverage`() {
        val ledger = RecoveredEdgeLedger()
        val token = assertNotNull(ledger.propose(edge))
        ledger.acknowledge(token)

        assertFailsWith<IllegalArgumentException> { ledger.acknowledge(token) }
        assertFailsWith<IllegalArgumentException> { ledger.acknowledge(EmissionToken(999, edge)) }
        assertFailsWith<IllegalArgumentException> { ledger.acknowledge(EmissionToken(1000, other)) }
        assertTrue(!ledger.contains(other))
    }

    @Test
    fun `distinct edges remain distinct`() {
        val ledger = RecoveredEdgeLedger()
        val first = assertNotNull(ledger.propose(edge))
        val second = assertNotNull(ledger.propose(other))
        assertNotSame(first, second)

        ledger.acknowledge(first)
        assertTrue(ledger.contains(edge))
        assertTrue(!ledger.contains(other))
    }

    @Test
    fun `map acknowledgement does not suppress ring-derived unique edges`() {
        val ringGate = UniqueEdgeGate()
        val ledger = RecoveredEdgeLedger()

        // The ring gate is untouched by recovery commits: a later identical
        // ring observation is still emittable until the ring path acknowledges it.
        val token = assertNotNull(ledger.propose(edge))
        ledger.acknowledge(token)
        assertTrue(ledger.contains(edge))
        assertTrue(ringGate.canEmit(edge))

        ringGate.acknowledge(edge)
        assertTrue(!ringGate.canEmit(edge))
    }
}
