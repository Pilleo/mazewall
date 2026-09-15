package io.mazewall.ffi.memory

/** Acquires the two daemon rentals and ACK storage together, rolling back partial acquisition. */
internal class SeccompSessionBuffers private constructor(
    private val arena: NativeArena,
    public val ack: ManagedSegment,
    private val notifLease: SegmentLease,
    private val respLease: SegmentLease,
) : AutoCloseable {
    private var notifReleased = false
    private var respReleased = false
    private var arenaClosed = false

    public val notif: ManagedSegment get() = notifLease.segment
    public val resp: ManagedSegment get() = respLease.segment
    public val isClosed: Boolean get() = arenaClosed

    /** Drains every owned resource; failed releases remain owned for a later retry. */
    @Suppress("TooGenericExceptionCaught")
    override fun close() {
        var primary: Throwable? = null

        fun retire(action: () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                val prior = primary
                if (prior == null) primary = failure else prior.addSuppressed(failure)
            }
        }
        if (!notifReleased) {
            retire {
            notifLease.close()
            notifReleased = true
        }
        }
        if (!respReleased) {
            retire {
            respLease.close()
            respReleased = true
        }
        }
        if (!arenaClosed) {
            retire {
            arena.close()
            arenaClosed = true
        }
        }
        primary?.let { throw it }
    }

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
