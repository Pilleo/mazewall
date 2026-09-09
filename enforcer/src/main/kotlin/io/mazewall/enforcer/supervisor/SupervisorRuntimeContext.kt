package io.mazewall.enforcer.supervisor

import io.mazewall.core.Pid
import io.mazewall.core.Tid
import java.util.concurrent.ConcurrentHashMap

/** Installation-scoped JVM state used while a supervisor listener resolves tracee threads. */
internal class SupervisorRuntimeContext(
    private val processIdentityResolver: ProcessIdentityResolver = ProcProcessIdentityResolver(),
) : AutoCloseable {
    private val threads = ConcurrentHashMap<Tid, Thread>()
    private val spawnAuthorizations = SpawnAuthorizationStore()
    private var closed = false

    @Synchronized
    fun register(
        tid: Tid,
        thread: Thread = Thread.currentThread(),
    ) {
        check(!closed) { "Supervisor runtime context is closed" }
        threads[tid] = thread
    }

    fun unregister(tid: Tid) {
        threads.remove(tid)
    }

    fun resolve(tid: Tid): Thread? = threads[tid]

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
