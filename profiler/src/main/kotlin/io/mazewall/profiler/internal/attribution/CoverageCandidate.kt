package io.mazewall.profiler.internal.attribution

/**
 * Immutable session-bound retained-edge candidate from the bounded kernel map.
 *
 * A candidate is historical evidence of an observation admitted at capture time.
 * Validation must not replay it as a current marker transition or advance task
 * replay state. Coverage membership commits only through the shared
 * collector-owned acknowledgement boundary.
 */
internal class CoverageCandidate(
    val sessionEpoch: SessionEpoch,
    val canonicalStackTraceId: StackTraceId,
    val syscallNumber: Int,
    val captureFlags: Int,
    val representative: CoverageRepresentative,
    val processIncarnation: ProcessIncarnation,
    val attachmentGeneration: Int = 0,
) {
    val observedKtimeNanos: Long get() = representative.observedKtimeNanos
    val tgid: Int get() = representative.tgid
    val tid: Int get() = representative.tid
    val invocationId: InvocationId get() = representative.invocationId
    val markerSequence: Long get() = representative.markerSequence
    val args: List<Long> get() = representative.args

    init {
        require(sessionEpoch.value > 0) { "session epoch must be positive" }
        require(syscallNumber >= 0) { "syscall number must be non-negative" }
        require(processIncarnation.value > 0) { "process incarnation must be positive" }
    }

    /** Canonical edge identity shared with the ring-derived unique-edge gate. */
    fun edge(): UniqueEdgeGate.Edge =
        UniqueEdgeGate.Edge(
            stackTraceId = canonicalStackTraceId,
            syscallNumber = syscallNumber,
            captureFlags = captureFlags,
        )

    fun copy(args: List<Long> = this.args): CoverageCandidate =
        CoverageCandidate(
            sessionEpoch,
            canonicalStackTraceId,
            syscallNumber,
            captureFlags,
            representative.copyArgs(args),
            processIncarnation,
            attachmentGeneration,
        )
}

/** Kernel-observed scalars, owned without exposing mutable syscall arguments. */
internal class CoverageRepresentative(
    val observedKtimeNanos: Long,
    val tgid: Int,
    val tid: Int,
    val invocationId: InvocationId,
    val markerSequence: Long,
    args: List<Long>,
) {
    val args: List<Long> = java.util.List.copyOf(args)

    init {
        require(markerSequence > 0) { "marker sequence must be positive" }
        require(tgid > 0) { "tgid must be positive" }
        require(tid > 0) { "tid must be positive" }
        require(args.size == RAW_SYSCALL_ARGUMENTS) { "coverage candidate must carry six raw syscall arguments" }
    }

    fun copyArgs(args: List<Long>): CoverageRepresentative = CoverageRepresentative(observedKtimeNanos, tgid, tid, invocationId, markerSequence, args)

    private companion object {
        const val RAW_SYSCALL_ARGUMENTS = 6
    }
}
