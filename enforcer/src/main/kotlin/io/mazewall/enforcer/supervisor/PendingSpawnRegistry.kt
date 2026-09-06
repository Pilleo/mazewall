package io.mazewall.enforcer.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.state.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.*

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
    private const val AUTHORIZATION_TTL_NANOS = 10_000_000_000L

    private val registry = ConcurrentHashMap<Tid, PendingSpawn>()

    private class PendingSpawn(
        val stackTrace: List<StackTraceElement>,
        private val registeredAtNanos: Long,
    ) {
        fun isExpired(nowNanos: Long): Boolean = nowNanos - registeredAtNanos > AUTHORIZATION_TTL_NANOS
    }

    /**
     * Registers the authorized stack trace for a parent thread currently spawning a process.
     */
    fun register(parentTid: Tid, stackTrace: List<StackTraceElement>, nowNanos: Long = System.nanoTime()) {
        val now = nowNanos
        registry[parentTid] = PendingSpawn(stackTrace, now)
        registry.entries.removeIf { it.value.isExpired(now) }
    }

    /**
     * Retrieves the authorized stack trace for a given parent TID.
     */
    fun get(parentTid: Tid, nowNanos: Long = System.nanoTime()): List<StackTraceElement>? {
        val entry = registry[parentTid] ?: return null
        if (entry.isExpired(nowNanos)) {
            registry.remove(parentTid, entry)
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
