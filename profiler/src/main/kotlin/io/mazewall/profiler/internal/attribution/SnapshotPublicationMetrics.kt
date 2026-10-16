package io.mazewall.profiler.internal.attribution

/**
 * Immutable accounting for live snapshot publication.
 *
 * The values describe retained-state and publication cost, not capture
 * correctness.  They are intentionally separate from [SessionIntegrity] so
 * a fast run cannot be mistaken for a complete run.
 */
internal data class SnapshotPublicationMetrics(
    public val serializedBytes: Long = 0,
    public val peakRetainedBytes: Long = 0,
    public val publicationCount: Long = 0,
    public val coalescedPublicationCount: Long = 0,
    public val publicationLatencyNanos: Long = 0,
)
