package io.mazewall.ffi.memory

import io.mazewall.ffi.Layouts
import java.lang.foreign.MemoryLayout
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Reuses fixed-size native buffers under generation-specific leases.
 *
 * [owned] pools own a shared arena. [borrowed] pools leave arena lifetime and thread confinement
 * to the caller. Closing either pool with outstanding leases fails without changing its state.
 * Overflow allocations are retained: [poolSize] is a preallocation count, not a retention limit.
 */
internal class SegmentPool private constructor(
    private val layout: MemoryLayout?,
    public val byteSize: Long,
    private val poolSize: Int,
    private val arena: NativeArena,
    private val ownsArena: Boolean,
) : AutoCloseable {
    private val queue = ConcurrentLinkedQueue<ManagedSegment>()
    private val checkedOut = mutableMapOf<Long, SegmentLease>()
    private var generation = 0L
    private var closed = false

    init {
        repeat(poolSize) { queue.offer(allocate()) }
    }

    /** Returns an exclusive, zeroed rental. Close the lease, never return a raw segment. */
    @Synchronized
    public fun rent(): SegmentLease {
        check(!closed) { "SegmentPool is closed" }
        check(arena.isAlive) { "SegmentPool arena is closed" }
        val nextGeneration = Math.incrementExact(generation)
        val buffer = queue.poll() ?: allocate()
        buffer.fill(0)
        val lease = SegmentLease(this, nextGeneration, buffer)
        generation = nextGeneration
        checkedOut[nextGeneration] = lease
        return lease
    }

    @Synchronized
    internal fun checkedSegment(lease: SegmentLease): ManagedSegment {
        require(lease.owner === this) { "Segment lease belongs to another pool" }
        check(checkedOut[lease.generation] === lease) { "Segment lease is closed" }
        check(arena.isAlive) { "SegmentPool arena is closed" }
        return lease.buffer
    }

    @Synchronized
    internal fun returnLease(lease: SegmentLease) {
        require(lease.owner === this && lease.buffer.byteSize() == byteSize) {
            "Segment lease does not belong to this pool or has the wrong size"
        }
        // Exact lease identity, not the reusable native address, is the authority to return.
        if (checkedOut[lease.generation] !== lease) return
        checkedOut.remove(lease.generation)
        queue.offer(lease.buffer)
    }

    /** Rejects outstanding leases atomically; callers may close them and retry. */
    @Synchronized
    override fun close() {
        if (closed) return
        check(checkedOut.isEmpty()) { "Cannot close SegmentPool with ${checkedOut.size} outstanding lease(s)" }
        if (ownsArena) arena.close()
        queue.clear()
        closed = true
    }

    private fun allocate(): ManagedSegment = layout?.let(arena::allocate) ?: arena.allocate(byteSize)

    public companion object {
        public fun owned(
            layout: MemoryLayout,
            poolSize: Int = 16,
        ): SegmentPool = ownedPool(layout, layout.byteSize(), poolSize)

        public fun owned(
            byteSize: Long,
            poolSize: Int = 16,
        ): SegmentPool = ownedPool(null, byteSize, poolSize)

        public fun borrowed(
            layout: MemoryLayout,
            arena: NativeArena,
            poolSize: Int = 16,
        ): SegmentPool = borrowedPool(layout, layout.byteSize(), poolSize, arena)

        public fun borrowed(
            byteSize: Long,
            arena: NativeArena,
            poolSize: Int = 16,
        ): SegmentPool = borrowedPool(null, byteSize, poolSize, arena)

        private fun validate(
            byteSize: Long,
            poolSize: Int,
        ) {
            require(byteSize > 0) { "SegmentPool byteSize must be positive" }
            require(poolSize >= 0) { "SegmentPool poolSize must not be negative" }
        }

        @Suppress("TooGenericExceptionCaught") // Acquisition boundary: dispose only our arena and rethrow unchanged.
        private fun ownedPool(layout: MemoryLayout?, byteSize: Long, poolSize: Int): SegmentPool {
            validate(byteSize, poolSize)
            val arena = NativeArena.ofShared()
            try {
                return SegmentPool(layout, byteSize, poolSize, arena, ownsArena = true)
            } catch (failure: Throwable) {
                try {
                    arena.close()
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
                throw failure
            }
        }

        private fun borrowedPool(
            layout: MemoryLayout?,
            byteSize: Long,
            poolSize: Int,
            arena: NativeArena,
        ): SegmentPool {
            validate(byteSize, poolSize)
            return SegmentPool(layout, byteSize, poolSize, arena, ownsArena = false)
        }

        /** Process-lifetime daemon pool; individual sessions close leases only. */
        public val SECCOMP_NOTIF_POOL: SegmentPool = owned(Layouts.SECCOMP_NOTIF)

        /** Process-lifetime daemon pool; individual sessions close leases only. */
        public val SECCOMP_NOTIF_RESP_POOL: SegmentPool = owned(Layouts.SECCOMP_NOTIF_RESP)
    }
}
