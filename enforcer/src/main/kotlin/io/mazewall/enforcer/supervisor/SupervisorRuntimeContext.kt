package io.mazewall.enforcer.supervisor

import io.mazewall.core.Tid
import java.util.concurrent.ConcurrentHashMap

/** Installation-scoped JVM state used while a supervisor listener resolves tracee threads. */
internal class SupervisorRuntimeContext {
    private val threads = ConcurrentHashMap<Tid, Thread>()

    fun register(
        tid: Tid,
        thread: Thread = Thread.currentThread(),
    ) {
        threads[tid] = thread
    }

    fun unregister(tid: Tid) {
        threads.remove(tid)
    }

    fun resolve(tid: Tid): Thread? = threads[tid]
}
