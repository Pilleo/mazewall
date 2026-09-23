package io.mazewall.profiler.internal.attribution

/** A dictionary-complete, collector-retained coverage representative. */
internal class RecoveredCoverageEdge(
    val candidate: CoverageCandidate,
    val stackDefinition: StackDefinition,
) {
    init {
        require(candidate.canonicalStackTraceId == stackDefinition.stackTraceId)
    }

    fun edge(): UniqueEdgeGate.Edge = candidate.edge()
}
