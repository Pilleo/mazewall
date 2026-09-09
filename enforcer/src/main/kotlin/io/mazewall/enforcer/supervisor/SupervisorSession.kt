package io.mazewall.enforcer.supervisor

import io.mazewall.core.Tid
import io.mazewall.enforcer.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.state.*

/**
 * Idempotent task-registration lease. Closing it never retires the permanent
 * filter's runtime or listener; those belong to the installing thread's lifetime.
 */
public class SupervisorSession internal constructor(
    private val release: () -> Unit,
) : AutoCloseable {
    internal constructor(tid: Tid, runtime: SupervisorRuntimeContext) : this({ runtime.unregister(tid) })

    private val closed = java.util.concurrent.atomic
        .AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}
