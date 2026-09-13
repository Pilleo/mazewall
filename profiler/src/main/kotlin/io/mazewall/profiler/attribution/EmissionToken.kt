package io.mazewall.profiler.attribution

/**
 * Opaque proof that a specific collector-owned candidate was returned.
 *
 * The constructor and identity are intentionally not reproducible by callers;
 * acknowledgement must use the object handed out by this collector session.
 */
public class EmissionToken internal constructor(
    internal val candidateId: Long,
)
