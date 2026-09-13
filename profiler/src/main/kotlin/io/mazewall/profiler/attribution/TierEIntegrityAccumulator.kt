package io.mazewall.profiler.attribution

/** Owns mutable loss and attribution counters until a session snapshot is requested. */
internal class TierEIntegrityAccumulator {
    private var observedLosses: Long = 0L
    private var attributionFailures: Long = 0L
    private var virtualPinnedScopes: Long = 0L
    private var virtualPinnedNanos: Long = 0L

    fun recordLoss(count: Long) {
        require(count > 0) { "loss count must be positive" }
        observedLosses = Math.addExact(observedLosses, count)
    }

    fun recordAttributionFailure(count: Long = 1L) {
        require(count > 0) { "attribution failure count must be positive" }
        attributionFailures = Math.addExact(attributionFailures, count)
    }

    fun recordVirtualPinnedIntervals(
        scopes: Long,
        nanos: Long,
    ) {
        require(scopes >= 0 && nanos >= 0) { "virtual pin telemetry must be non-negative" }
        virtualPinnedScopes = Math.addExact(virtualPinnedScopes, scopes)
        virtualPinnedNanos = Math.addExact(virtualPinnedNanos, nanos)
    }

    fun snapshot(
        emissionMode: TierEEmissionMode,
        captureDomain: ObservationScope,
    ): SessionIntegrity =
        SessionIntegrity(
            observedLosses = observedLosses,
            attributionFailures = attributionFailures,
            emissionMode = emissionMode,
            virtualPinnedScopes = virtualPinnedScopes,
            virtualPinnedNanos = virtualPinnedNanos,
            captureDomain = captureDomain,
        )
}
