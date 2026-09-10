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
            if (cancelled()) fail("cancelled")
            val remaining = deadline.remainingMillis().toLong()
            if (remaining <= 0) fail("timed out")
            if (lock.tryLock(minOf(remaining, CANCELLATION_SLICE_MS), TimeUnit.MILLISECONDS)) {
                if (cancelled()) {
                    lock.unlock()
                    fail("cancelled")
                }
                if (deadline.isExpired()) {
                    lock.unlock()
                    fail("timed out")
                }
                return
            }
        }
    }

    private fun fail(reason: String): Nothing = throw IOException("portal admission $reason")
}
