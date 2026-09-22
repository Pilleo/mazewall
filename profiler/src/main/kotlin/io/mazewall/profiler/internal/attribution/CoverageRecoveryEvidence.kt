package io.mazewall.profiler.internal.attribution

/** Where an acknowledged coverage edge was first observed. */
internal enum class CoverageDiscoverySource {
    RING,
    MAP,
}

/**
 * Additive recovery accounting. Existing event-history and integrity fields
 * remain authoritative; a recovered singleton never upgrades an incomplete
 * ring-transport or event-history verdict.
 */
internal data class CoverageRecoveryEvidence(
    val mapCapacity: Int = 0,
    val entriesObserved: Long = 0,
    val insertionFailures: Long = 0,
    val pendingCandidates: Long = 0,
    val recoveredEdges: Long = 0,
    val acknowledgementRetries: Long = 0,
    val scanBatches: Long = 0,
    val overdueSweeps: Long = 0,
    val lastScanLatencyNanos: Long = 0,
    val mapFirstAcknowledgedEdges: Long = 0,
    val confirmedRingLossRecoveries: Long = 0,
    /**
     * Dependency-relative recovery latency of the most recent commit:
     * acknowledgement time minus the later of map ownership and dictionary
     * readiness. Dictionary waiting is reported separately via pending counts.
     */
    val lastRecoveryLatencyNanos: Long = 0,
    val maxRecoveryLatencyNanos: Long = 0,
) {
    init {
        require(mapCapacity >= 0) { "map capacity must be non-negative" }
        require(entriesObserved >= 0 && insertionFailures >= 0 && pendingCandidates >= 0) {
            "recovery counters must be non-negative"
        }
        require(recoveredEdges >= 0 && acknowledgementRetries >= 0 && scanBatches >= 0) {
            "recovery counters must be non-negative"
        }
        require(overdueSweeps >= 0 && lastScanLatencyNanos >= 0) {
            "scan accounting must be non-negative"
        }
        require(mapFirstAcknowledgedEdges >= 0 && confirmedRingLossRecoveries >= 0) {
            "discovery accounting must be non-negative"
        }
        require(lastRecoveryLatencyNanos >= 0 && maxRecoveryLatencyNanos >= 0) {
            "latency accounting must be non-negative"
        }
        require(confirmedRingLossRecoveries <= mapFirstAcknowledgedEdges) {
            "confirmed ring-loss recoveries cannot exceed map-first acknowledgements"
        }
    }
}
