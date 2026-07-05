package io.mazewall.enforcer.supervisor

import io.mazewall.core.Tid
import java.util.concurrent.ConcurrentHashMap

/**
 * A global registry that stores the authorized stack traces of parent threads
 * that are currently suspended in a process spawn syscall (fork, vfork, or clone).
 *
 * This allows the supervisor to propagate the parent's context to the child
 * process when it subsequently calls execve.
 */
internal object PendingSpawnRegistry {
    private val registry = ConcurrentHashMap<Tid, List<StackTraceElement>>()

    /**
     * Registers the authorized stack trace for a parent thread currently spawning a process.
     */
    fun register(parentTid: Tid, stackTrace: List<StackTraceElement>) {
        registry[parentTid] = stackTrace
    }

    /**
     * Retrieves the authorized stack trace for a given parent TID.
     */
    fun get(parentTid: Tid): List<StackTraceElement>? {
        return registry[parentTid]
    }

    /**
     * Removes the authorized stack trace for a given parent TID.
     * This should be called after the child successfully execs or the parent returns.
     */
    fun remove(parentTid: Tid) {
        registry.remove(parentTid)
    }
}
