package io.mazewall.portal

import io.mazewall.core.Deadline
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Admission is part of the RPC budget, and cancellation is independent of the lock owner. */
internal object PortalAdmission {
    private const val CANCELLATION_SLICE_MS = 50L

    fun acquire(
        lock: ReentrantLock,
        deadline: Deadline,
        cancelled: () -> Boolean,
    ) {
        while (true) {
            if (cancelled()) throw IOException("portal admission cancelled")
            val remaining = deadline.remainingMillis().toLong()
            if (remaining <= 0) throw IOException("portal admission timed out")
            if (lock.tryLock(minOf(remaining, CANCELLATION_SLICE_MS), TimeUnit.MILLISECONDS)) {
                if (cancelled()) {
                    lock.unlock()
                    throw IOException("portal admission cancelled")
                }
                if (deadline.isExpired()) {
                    lock.unlock()
                    throw IOException("portal admission timed out")
                }
                return
            }
        }
    }
}
