package io.mazewall.ffi.memory


import io.mazewall.ffi.Layouts
import java.lang.foreign.MemoryLayout
import java.util.concurrent.ConcurrentHashMap
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
    private val checkedOut = ConcurrentHashMap.newKeySet<Long>()

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
            checkout(segment)
            segment.fill(0)
            return segment
        }
        // Fallback: allocate a new one if the pool is exhausted
        val fallback = if (layout != null) {
            arena.allocate(layout)
        } else {
            arena.allocate(byteSize)
        }
        checkout(fallback)
        fallback.fill(0)
        return fallback
    }

    /**
     * Releases a segment back to the pool.
     *
     * The pool retains valid overflow segments so later concurrency waves reuse the native
     * allocation. [poolSize] controls preallocation, not a retention limit: the shared arena
     * cannot free individual segments that exceed such a limit.
     *
     * Release is idempotent: a second return of the same native address is ignored so a
     * double [io.mazewall.platform.seccomp.daemon.SeccompSessionHandler.close] cannot enqueue
     * one buffer twice.
     */
    public fun release(segment: ManagedSegment) {
        if (segment.byteSize() != byteSize) {
            return
        }
        if (checkedOut.remove(segment.address())) {
            queue.offer(segment)
        }
    }

    private fun checkout(segment: ManagedSegment) {
        checkedOut.add(segment.address())
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
