package io.mazewall.enforcer.supervisor

import io.mazewall.core.Tid

/** Count admitted work across every listener of a daemon, before request preparation. */
internal class SupervisorAdmission {
    private val counts = mutableMapOf<Tid, Int>()
    private var total = 0

    @Synchronized
    fun acquire(tid: Tid): AutoCloseable? {
        val count = counts[tid] ?: 0
        if (total >= MAX_TOTAL || count >= MAX_PER_TID) return null
        total++
        counts[tid] = count + 1
        var released = false
        return AutoCloseable {
            synchronized(this) {
                if (!released) {
                    released = true
                    total--
                    val remaining = checkNotNull(counts[tid]) - 1
                    if (remaining == 0) counts.remove(tid) else counts[tid] = remaining
                }
            }
        }
    }

    private companion object {
        const val MAX_TOTAL = 128
        const val MAX_PER_TID = 8
    }
}
