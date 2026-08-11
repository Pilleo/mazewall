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
    private val registry = ConcurrentHashMap<Tid, PendingSpawn>()

    private class PendingSpawn(val stackTrace: List<StackTraceElement>, val timestamp: Long)

    /**
     * Registers the authorized stack trace for a parent thread currently spawning a process.
     */
    fun register(parentTid: Tid, stackTrace: List<StackTraceElement>) {
        val now = System.currentTimeMillis()
        registry[parentTid] = PendingSpawn(stackTrace, now)
        // Clean up expired entries (older than 10 seconds to be very safe)
        registry.entries.removeIf { now - it.value.timestamp > 10000 }
    }

    /**
     * Retrieves the authorized stack trace for a given parent TID.
     */
    fun get(parentTid: Tid): List<StackTraceElement>? {
        val entry = registry[parentTid] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > 10000) {
            registry.remove(parentTid)
            return null
        }
        return entry.stackTrace
    }

    /**
     * Removes the authorized stack trace for a given parent TID.
     */
    fun remove(parentTid: Tid) {
        registry.remove(parentTid)
    }
}
