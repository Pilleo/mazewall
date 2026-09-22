package io.mazewall.profiler.internal.attribution

/** Owns mutable loss and attribution counters until a session snapshot is requested. */
internal class TierEIntegrityAccumulator {
    private var observedLosses: Long = 0L
    private var attributionFailures: Long = 0L
    private var ambientInactiveObservations: Long = 0L
    private var outOfDomainAttempts: Long = 0L
    private var virtualPinnedScopes: Long = 0L
    private var virtualPinnedNanos: Long = 0L
    private var lifecycleInvalidations: Long = 0L
    private var dictionaryIntegrityFailures: Long = 0L
    private var dictionaryTransport = DictionaryTransportEvidence()
    private var reconciliation = TierEReconciliation()
    private var recoveryEvidence = CoverageRecoveryEvidence()

    fun recordLoss(count: Long) {
        require(count > 0) { "loss count must be positive" }
        observedLosses = Math.addExact(observedLosses, count)
    }

    fun recordAttributionFailure(count: Long = 1L) {
        require(count > 0) { "attribution failure count must be positive" }
        attributionFailures = Math.addExact(attributionFailures, count)
    }

    fun recordAmbientInactive(count: Long) {
        require(count > 0) { "ambient inactive count must be positive" }
        ambientInactiveObservations = Math.addExact(ambientInactiveObservations, count)
    }

    fun recordOutOfDomainAttempt(count: Long = 1L) {
        require(count > 0) { "out-of-domain attempt count must be positive" }
        outOfDomainAttempts = Math.addExact(outOfDomainAttempts, count)
    }

    fun recordLifecycleInvalidation(count: Long = 1L) {
        require(count > 0) { "lifecycle invalidation count must be positive" }
        lifecycleInvalidations = Math.addExact(lifecycleInvalidations, count)
    }

    fun recordDictionaryIntegrityFailure(count: Long = 1L) {
        require(count > 0) { "dictionary integrity failure count must be positive" }
        dictionaryIntegrityFailures = Math.addExact(dictionaryIntegrityFailures, count)
    }

    fun recordVirtualPinnedIntervals(
        scopes: Long,
        nanos: Long,
    ) {
        require(scopes >= 0 && nanos >= 0) { "virtual pin telemetry must be non-negative" }
        virtualPinnedScopes = Math.addExact(virtualPinnedScopes, scopes)
        virtualPinnedNanos = Math.addExact(virtualPinnedNanos, nanos)
    }

    fun recordDictionaryTransport(evidence: DictionaryTransportEvidence) {
        dictionaryTransport = evidence
    }

    fun recordReconciliation(value: TierEReconciliation) {
        reconciliation = value
    }

    fun recordRecoveryEvidence(evidence: CoverageRecoveryEvidence) {
        recoveryEvidence = evidence
    }

    fun snapshot(
        emissionMode: TierEEmissionMode,
        captureDomain: ObservationScope,
    ): SessionIntegrity =
        SessionIntegrity(
            observedLosses = observedLosses,
            attributionFailures = attributionFailures,
            ambientInactiveObservations = ambientInactiveObservations,
            outOfDomainAttempts = outOfDomainAttempts,
            lifecycleInvalidations = lifecycleInvalidations,
            emissionMode = emissionMode,
            virtualPinnedScopes = virtualPinnedScopes,
            virtualPinnedNanos = virtualPinnedNanos,
            captureDomain = captureDomain,
            dictionaryTransport = dictionaryTransport,
            dictionaryIntegrityFailures = dictionaryIntegrityFailures,
            reconciliation = reconciliation,
            recoveryEvidence = recoveryEvidence,
        )
}
