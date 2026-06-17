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
    val landlockAppliedReads: Set<SandboxedPath>? = null,
    val landlockAppliedWrites: Set<SandboxedPath>? = null,
    val engineState: SeccompInstallationState = SeccompInstallationState.Uninitialized,
    val allowsMmapExec: Boolean = true,
    val allowsNonThreadClone: Boolean = true,
    val allowsUnsafePrctl: Boolean = true
) {
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

    fun withLandlockPaths(
        reads: Set<SandboxedPath>,
        writes: Set<SandboxedPath>
    ): ContainerState = copy(
        landlockAppliedReads = reads,
        landlockAppliedWrites = writes
    )

    fun withEngineState(next: SeccompInstallationState): ContainerState = copy(engineState = next)
}
