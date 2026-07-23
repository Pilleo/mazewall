package io.mazewall.enforcer

import io.mazewall.PolicyDefinition
import io.mazewall.core.SandboxedPath
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import io.mazewall.seccomp.SeccompInstallationState

/**
 * Immutable security state of a container (process or thread).
 */
internal data class ContainerState(
    val filterDepth: Int = 0,
    val allowedSyscalls: Set<Syscall>? = null,
    val defaultAction: SeccompAction = SeccompAction.ACT_ALLOW,
    val syscallActions: Map<Syscall, SeccompAction> = emptyMap(),
    val landlockPolicy: PolicyDefinition<*>? = null,
    val engineState: SeccompInstallationState = SeccompInstallationState.Uninitialized,
    val allowsMmapExec: Boolean = true,
    val allowsNonThreadClone: Boolean = true,
    val allowsUnsafePrctl: Boolean = true
) {
    /** Returns true if the given [syscall] is unconditionally allowed by this state. */
    fun isSyscallAllowed(syscall: Syscall): Boolean {
        val action = syscallActions[syscall] ?: defaultAction
        return action == SeccompAction.ACT_ALLOW
    }

    fun withNewSeccompPolicy(
        toInstall: PolicyDefinition<*>,
        newBlocks: Map<Syscall, SeccompAction>,
        newDefaultAction: SeccompAction
    ): ContainerState {
        val mergedActions = syscallActions.toMutableMap()
        for ((sys, action) in newBlocks) {
            mergedActions[sys] = action
        }

        val nextDefaultAction = if (newDefaultAction.priority > defaultAction.priority) {
            newDefaultAction
        } else {
            defaultAction
        }

        val nextAllowedSyscalls = if (toInstall.defaultAction != SeccompAction.ACT_ALLOW) {
            val toInstallAllowed = Syscall.entries.filter { toInstall.isSyscallAllowed(it) }.toSet()
            allowedSyscalls?.intersect(toInstallAllowed) ?: toInstallAllowed
        } else {
            allowedSyscalls
        }

        return copy(
            filterDepth = filterDepth + 1,
            syscallActions = mergedActions,
            defaultAction = nextDefaultAction,
            allowedSyscalls = nextAllowedSyscalls,
            allowsMmapExec = allowsMmapExec && toInstall.allowMmapExec,
            allowsNonThreadClone = allowsNonThreadClone && toInstall.allowNonThreadClone,
            allowsUnsafePrctl = allowsUnsafePrctl && toInstall.allowUnsafePrctl
        )
    }

    fun withLandlockPolicy(
        policy: PolicyDefinition<*>
    ): ContainerState = copy(
        landlockPolicy = policy
    )

    fun withEngineState(next: SeccompInstallationState): ContainerState = copy(engineState = next)

    internal companion object {
        /**
         * Resolves the cumulative security state of the current thread,
         * merging both thread-local and global process-wide restrictions.
         *
         * Thread-Safety and Concurrency:
         * This function reads the thread-local state (`ThreadStateRegistry.state`) and the
         * global process state (`ProcessStateRegistry.state`). Because [ContainerState] and
         * its contained collections (e.g. [syscallActions], [allowedSyscalls]) are immutable,
         * and [ProcessStateRegistry.state] is managed via an atomic reference, this function
         * is fully thread-safe and robust against concurrent modifications of global policies.
         *
         * However, modifying process-wide global policies concurrently while task executors
         * are active or shutting down is unsupported, as the state resolution of late-running
         * tasks may interleave with global state transitions.
         */
        fun resolveCurrentState(): ContainerState {
            return ThreadStateRegistry.resolveCurrentState(ProcessStateRegistry.state)
        }
    }
}
