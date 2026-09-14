package io.mazewall.profiler.api

import io.mazewall.platform.lifecycle.Tid
import java.util.concurrent.ConcurrentHashMap

/** Mutable state owned by one profiling operation or wrapped-executor lifetime. */
internal class ProfilerSessionState {
    private val threads = ConcurrentHashMap<Tid, Thread>()

    fun register(
        tid: Tid,
        thread: Thread,
    ) {
        threads[tid] = thread
    }

    fun remove(tid: Tid) {
        threads.remove(tid)
    }

    fun threadFor(tid: Tid): Thread? = threads[tid]

    fun clear() {
        threads.clear()
    }
}
