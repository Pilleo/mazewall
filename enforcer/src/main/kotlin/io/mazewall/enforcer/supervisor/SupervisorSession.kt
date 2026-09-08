package io.mazewall.enforcer.supervisor

import io.mazewall.core.Tid
import io.mazewall.enforcer.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.state.*

/**
 * Resource token representing an active, contained supervisor session.
 *
 * Implements [AutoCloseable] to ensure that when a contained task completes or exits,
 * its installation-scoped thread registration is cleaned up correctly
 * to prevent classloader leaks and inconsistent thread state.
 */
public class SupervisorSession internal constructor(
    private val tid: Tid,
    private val runtime: SupervisorRuntimeContext = SupervisorInstaller.compatibilityRuntime(),
) : AutoCloseable {
    override fun close() {
        runtime.unregister(tid)
    }
}
