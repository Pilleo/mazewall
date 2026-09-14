package io.mazewall.profiler.internal.attribution

/** Deduplicates only edges whose downstream emission has been acknowledged. */
internal class UniqueEdgeGate {
    private val acknowledged = mutableSetOf<Edge>()

    fun canEmit(edge: Edge): Boolean = edge !in acknowledged

    fun acknowledge(edge: Edge) {
        acknowledged.add(edge)
    }

    data class Edge(
        val stackTraceId: StackTraceId,
        val syscallNumber: Int,
        val captureFlags: Int,
    )
}
