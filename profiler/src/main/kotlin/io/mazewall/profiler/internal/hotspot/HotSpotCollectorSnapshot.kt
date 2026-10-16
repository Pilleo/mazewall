package io.mazewall.profiler.internal.hotspot

private const val STARTUP_NANOS = 10_000_000_000L
private const val SESSION_NANOS = 60_000_000_000L
private const val STOP_NANOS = 2_000_000_000L

internal data class HotSpotCollectorTiming(
    val startupNanos: Long = STARTUP_NANOS,
    val sessionNanos: Long = SESSION_NANOS,
    val stopNanos: Long = STOP_NANOS,
) {
    init {
        require(startupNanos in 1..STARTUP_NANOS && sessionNanos in 1..SESSION_NANOS && stopNanos in 1..STOP_NANOS)
    }
}

internal data class HotSpotCollectorSnapshot(
    val state: HotSpotSessionState,
    val evidence: StructuredProfileEvidence?,
    val stderr: String,
    val discardedStderrBytes: Long,
    val pendingOwnership: Boolean,
)

internal class HotSpotSessionFailure(
    val snapshot: HotSpotCollectorSnapshot,
    val owner: HotSpotCollectorProcess,
) : IllegalStateException("HotSpot collector session failed", (snapshot.state as? HotSpotSessionState.Failed)?.failures?.firstOrNull()) {
    init {
        (snapshot.state as? HotSpotSessionState.Failed)?.failures?.drop(1)?.forEach(::addSuppressed)
    }
}

internal class HotSpotCollectorExitFailure(
    val exitCode: Int,
) : IllegalStateException("HotSpot collector exited with code $exitCode")
