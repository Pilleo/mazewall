package io.mazewall.ffi.memory

/**
 * Exclusive rental for one pool generation. [close] is idempotent, including after re-rental.
 *
 * [segment] rejects access after return. A previously retrieved segment must not escape the
 * lease lifetime or race with [close]; native aliases cannot be revoked while the pool reuses
 * their allocation. Borrowed confined arenas retain their normal thread restrictions.
 */
internal class SegmentLease internal constructor(
    internal val owner: SegmentPool,
    internal val generation: Long,
    internal val buffer: ManagedSegment,
) : AutoCloseable {
    public val segment: ManagedSegment get() = owner.checkedSegment(this)

    override fun close() {
        owner.returnLease(this)
    }
}
