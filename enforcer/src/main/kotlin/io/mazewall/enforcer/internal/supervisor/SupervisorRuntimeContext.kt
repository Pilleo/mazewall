package io.mazewall.enforcer.internal.supervisor

import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.lifecycle.Tid
import java.util.concurrent.ConcurrentHashMap

/** Installation-scoped JVM state used while a supervisor listener resolves tracee threads. */
internal class SupervisorRuntimeContext(
    private val processIdentityResolver: ProcessIdentityResolver = ProcProcessIdentityResolver(),
) : AutoCloseable {
    private data class Registration(
        val thread: Thread,
        val leases: Int,
    )

    private val threads = ConcurrentHashMap<Tid, Registration>()
    private val spawnAuthorizations = SpawnAuthorizationStore()
    private var closed = false

    @Synchronized
    fun register(
        tid: Tid,
        thread: Thread = Thread.currentThread(),
    ) {
        check(!closed) { "Supervisor runtime context is closed" }
        val previous = threads[tid]
        check(previous == null || previous.thread === thread) { "TID is already owned by another thread" }
        threads[tid] = Registration(thread, (previous?.leases ?: 0) + 1)
    }

    @Synchronized
    fun unregister(tid: Tid) {
        val previous = threads[tid] ?: return
        if (previous.leases == 1) {
            threads.remove(tid)
            if (threads.isEmpty()) spawnAuthorizations.clear()
        } else {
            threads[tid] = previous.copy(leases = previous.leases - 1)
        }
    }

    fun resolve(tid: Tid): Thread? = threads[tid]?.thread

    @Synchronized
    fun registerSpawn(
        pid: Pid,
        stack: List<StackTraceElement>,
        nowNanos: Long = System.nanoTime(),
    ): SpawnRegistrationResult {
        if (closed) return SpawnRegistrationResult.CLOSED
        val identity = processIdentityResolver.resolve(pid)
            ?: return SpawnRegistrationResult.IDENTITY_UNAVAILABLE
        return spawnAuthorizations.register(identity, stack, nowNanos)
    }

    @Synchronized
    fun consumeSpawn(
        pid: Pid,
        nowNanos: Long = System.nanoTime(),
    ): SpawnAuthorizationResult {
        if (closed) return SpawnAuthorizationResult.Rejected(SpawnRejection.CLOSED)
        val identity = processIdentityResolver.resolve(pid)
            ?: return SpawnAuthorizationResult.Rejected(SpawnRejection.IDENTITY_UNAVAILABLE)
        return spawnAuthorizations.consume(identity, nowNanos)
    }

    @Synchronized
    override fun close() {
        closed = true
        spawnAuthorizations.close()
        threads.clear()
    }
}
