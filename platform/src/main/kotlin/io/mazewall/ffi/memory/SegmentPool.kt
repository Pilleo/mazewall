package io.mazewall.ffi.memory


import io.mazewall.ffi.Layouts
import java.lang.foreign.MemoryLayout
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A thread-safe pool for reusing pre-allocated fixed-size FFM structures.
 * This prevents continual allocations and garbage collection pressure in high-throughput loops.
 */
public class SegmentPool private constructor(
    private val layout: MemoryLayout?,
    public val byteSize: Long,
    private val poolSize: Int,
    private val arena: NativeArena
) {
    private val queue = ConcurrentLinkedQueue<ManagedSegment>()

    public constructor(
        layout: MemoryLayout,
        poolSize: Int = 16,
        arena: NativeArena = NativeArena.ofShared()
    ) : this(layout, layout.byteSize(), poolSize, arena)

    public constructor(
        byteSize: Long,
        poolSize: Int = 16,
        arena: NativeArena = NativeArena.ofShared()
    ) : this(null, byteSize, poolSize, arena)

    init {
        for (i in 0 until poolSize) {
            val segment = if (layout != null) {
                arena.allocate(layout)
            } else {
                arena.allocate(byteSize)
            }
            segment.fill(0)
            queue.offer(segment)
        }
    }

    /**
     * Rents a segment from the pool.
     * The rented segment is guaranteed to be zeroed out.
     */
    public fun rent(): ManagedSegment {
        val segment = queue.poll()
        if (segment != null) {
            segment.fill(0)
            return segment
        }
        // Fallback: allocate a new one if the pool is exhausted
        val fallback = if (layout != null) {
            arena.allocate(layout)
        } else {
            arena.allocate(byteSize)
        }
        fallback.fill(0)
        return fallback
    }

    /**
     * Releases a segment back to the pool.
     */
    public fun release(segment: ManagedSegment) {
        if (segment.byteSize() == byteSize && queue.size < poolSize) {
            queue.offer(segment)
        }
    }

    public companion object {
        /**
         * Global pre-allocated pool for `seccomp_notif` structures.
         */
        public val SECCOMP_NOTIF_POOL: SegmentPool = SegmentPool(Layouts.SECCOMP_NOTIF)

        /**
         * Global pre-allocated pool for `seccomp_notif_resp` structures.
         */
        public val SECCOMP_NOTIF_RESP_POOL: SegmentPool = SegmentPool(Layouts.SECCOMP_NOTIF_RESP)
    }
}
