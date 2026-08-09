package io.mazewall.enforcer.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.state.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.*

import io.mazewall.core.Tid

/**
 * Resource token representing an active, contained supervisor session.
 *
 * Implements [AutoCloseable] to ensure that when a contained task completes or exits,
 * the thread's registration in [SupervisorInstaller.threadRegistry] is cleaned up correctly
 * to prevent classloader leaks and inconsistent thread state.
 */
public class SupervisorSession internal constructor(
    private val tid: Tid
) : AutoCloseable {
    override fun close() {
        SupervisorInstaller.unregisterThread(tid)
    }
}
