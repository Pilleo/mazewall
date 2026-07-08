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
         */
        fun resolveCurrentState(): ContainerState {
            val ts = ThreadStateRegistry.state
            val ps = ProcessStateRegistry.state

            val mergedActions = ts.syscallActions.toMutableMap()
            for ((sys, action) in ps.syscallActions) {
                val current = mergedActions[sys]
                if (current == null || action.priority > current.priority) {
                    mergedActions[sys] = action
                }
            }

            val mergedDefault = if (ts.defaultAction.priority > ps.defaultAction.priority) ts.defaultAction else ps.defaultAction

            val mergedAllowed = if (ts.allowedSyscalls == null) {
                ps.allowedSyscalls
            } else if (ps.allowedSyscalls == null) {
                ts.allowedSyscalls
            } else {
                ts.allowedSyscalls.intersect(ps.allowedSyscalls)
            }

            return ContainerState(
                filterDepth = ts.filterDepth + ps.filterDepth,
                syscallActions = mergedActions,
                defaultAction = mergedDefault,
                allowedSyscalls = mergedAllowed,
                allowsMmapExec = ts.allowsMmapExec && ps.allowsMmapExec,
                allowsNonThreadClone = ts.allowsNonThreadClone && ps.allowsNonThreadClone,
                allowsUnsafePrctl = ts.allowsUnsafePrctl && ps.allowsUnsafePrctl,
                landlockPolicy = ts.landlockPolicy ?: ps.landlockPolicy
            )
        }
    }
}
