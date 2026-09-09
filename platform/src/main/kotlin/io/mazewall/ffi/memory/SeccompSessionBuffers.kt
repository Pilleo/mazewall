package io.mazewall.ffi.memory

/** Acquires the two daemon rentals and ACK storage together, rolling back partial acquisition. */
internal class SeccompSessionBuffers private constructor(
    val arena: NativeArena,
    val ack: ManagedSegment,
    val notifLease: SegmentLease,
    val respLease: SegmentLease,
) {
    companion object {
        fun acquire(
            notifPool: SegmentPool = SegmentPool.SECCOMP_NOTIF_POOL,
            respPool: SegmentPool = SegmentPool.SECCOMP_NOTIF_RESP_POOL,
        ): SeccompSessionBuffers {
            val arena = NativeArena.ofConfined()
            var notif: SegmentLease? = null
            var transferred = false
            try {
                val ack = arena.allocate(1L)
                notif = notifPool.rent()
                val buffers = SeccompSessionBuffers(arena, ack, notif, respPool.rent())
                transferred = true
                return buffers
            } finally {
                if (!transferred) {
                    try {
                        notif?.close()
                    } finally {
                        arena.close()
                    }
                }
            }
        }
    }
}
