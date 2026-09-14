package io.mazewall.enforcer.api

import io.mazewall.enforcer.api.StacktraceScopingPolicy
import io.mazewall.enforcer.internal.supervisor.SupervisorDaemonManager

/**
 * Starts the external supervisor required by thread-scoped stacktrace-scoped policies.
 *
 * Call this before installing a process-wide policy when the same JVM will later install a
 * thread-scoped policy with [StacktraceScopingPolicy]. The supervisor is an implementation
 * detail; this method intentionally exposes no daemon, descriptor, or protocol state.
 */
public object Supervision {
    /** Starts external supervision without exposing daemon state or protocol types. */
    @JvmStatic
    public fun prepareSupervisor() {
        SupervisorDaemonManager.getInstance().getOrSpawnSharedDaemon()
    }
}
