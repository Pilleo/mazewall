package io.mazewall.profiler.internal.attribution

/**
 * Mode-independent acknowledged-edge ledger for retained-edge recovery.
 *
 * Ring-derived unique-edge suppression keeps its own [UniqueEdgeGate]; this ledger
 * commits coverage membership only, and acknowledging a map-derived edge never
 * suppresses later full-stream ring events or counts as a received ring event.
 * Tokens are opaque and session-local: a reconstructed, duplicated, or foreign
 * record cannot commit coverage state.
 */
internal class RecoveredEdgeLedger {
    private val acknowledged = mutableSetOf<UniqueEdgeGate.Edge>()
    private val outstanding = mutableMapOf<EmissionToken, UniqueEdgeGate.Edge>()
    private var nextCandidateId: Long = 1

    /** Proposes an edge for retained-output acknowledgement, or null if already covered. */
    fun propose(edge: UniqueEdgeGate.Edge): EmissionToken? {
        if (edge in acknowledged) return null
        outstanding.entries.firstOrNull { it.value == edge }?.let { return it.key }
        val token = EmissionToken(nextCandidateId++, edge)
        outstanding[token] = edge
        return token
    }

    /**
     * Commits coverage membership for the exact outstanding token.
     * A failed first retention leaves the edge eligible for retry via [propose].
     */
    fun acknowledge(token: EmissionToken) {
        val edge = outstanding.remove(token)
            ?: throw IllegalArgumentException("recovery token is stale, duplicated, or foreign")
        acknowledged.add(edge)
    }

    fun contains(edge: UniqueEdgeGate.Edge): Boolean = edge in acknowledged

    fun pendingCount(): Int = outstanding.size

    fun acknowledgedCount(): Int = acknowledged.size
}

/**
 * Retained-output sink for recovered edges. Returning true retains the edge in
 * the session's immutable result; the caller then acknowledges the exact token.
 * A false return (or exception) leaves the edge eligible for bounded retry.
 */
internal fun interface RecoveredEdgeSink {
    fun retain(edge: UniqueEdgeGate.Edge): Boolean
}
