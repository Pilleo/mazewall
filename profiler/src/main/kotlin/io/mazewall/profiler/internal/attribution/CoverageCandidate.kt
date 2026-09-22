package io.mazewall.profiler.internal.attribution

/**
 * Immutable session-bound retained-edge candidate from the bounded kernel map.
 *
 * A candidate is historical evidence of an observation admitted at capture time.
 * Validation must not replay it as a current marker transition or advance task
 * replay state. Coverage membership commits only through the shared
 * collector-owned acknowledgement boundary.
 */
internal data class CoverageCandidate(
    val sessionEpoch: SessionEpoch,
    val canonicalStackTraceId: StackTraceId,
    val syscallNumber: Int,
    val captureFlags: Int,
    val observedKtimeNanos: Long,
    val tgid: Int,
    val tid: Int,
    val invocationId: InvocationId,
    val markerSequence: Long,
    val args: List<Long>,
    val processIncarnation: ProcessIncarnation,
) {
    init {
        require(sessionEpoch.value > 0) { "session epoch must be positive" }
        require(syscallNumber >= 0) { "syscall number must be non-negative" }
        require(markerSequence > 0) { "marker sequence must be positive" }
        require(tgid > 0) { "tgid must be positive" }
        require(tid > 0) { "tid must be positive" }
        require(args.size == 6) { "coverage candidate must carry six raw syscall arguments" }
        require(processIncarnation.value > 0) { "process incarnation must be positive" }
    }

    /** Canonical edge identity shared with the ring-derived unique-edge gate. */
    fun edge(): UniqueEdgeGate.Edge =
        UniqueEdgeGate.Edge(
            stackTraceId = canonicalStackTraceId,
            syscallNumber = syscallNumber,
            captureFlags = captureFlags,
        )
}
