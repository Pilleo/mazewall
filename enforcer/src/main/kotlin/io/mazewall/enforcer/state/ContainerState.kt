package io.mazewall.enforcer.state

import io.mazewall.enforcer.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.PolicyDefinition
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.state.*
import io.mazewall.platform.lifecycle.SandboxedPath
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
import io.mazewall.seccomp.SeccompInstallationState

/**
 * Immutable security state of a container (process or thread).
 */
internal data class ContainerState(
    val filterDepth: Int = 0,
    val defaultAction: SeccompAction = SeccompAction.ACT_ALLOW,
    val syscallActions: Map<Syscall, SeccompAction> = emptyMap(),
    val landlockPolicy: PolicyDefinition<*>? = null,
    val engineState: SeccompInstallationState = SeccompInstallationState.Uninitialized,
    val allowsMmapExec: Boolean = true,
    val allowsNonThreadClone: Boolean = true,
    val allowsUnsafePrctl: Boolean = true,
    /** Terminal installation failure; independent of applied-policy and engine-state strength. */
    val installationEvidence: InstallationEvidence = InstallationEvidence(),
) {
    val installationFailure: Throwable? get() = installationEvidence.failure

    val allowedSyscalls: Set<Syscall>?
        get() =
            if (defaultAction == SeccompAction.ACT_ALLOW) {
                null
            } else {
                syscallActions.filterValues { it == SeccompAction.ACT_ALLOW }.keys
            }

    /** Returns the effective [SeccompAction] for [syscall], falling back to [defaultAction]. */
    fun effectiveAction(syscall: Syscall): SeccompAction = syscallActions[syscall] ?: defaultAction

    /** Returns true if the given [syscall] is unconditionally allowed by this state. */
    fun isSyscallAllowed(syscall: Syscall): Boolean = effectiveAction(syscall) == SeccompAction.ACT_ALLOW

    /**
     * Returns the effective [SeccompAction] for a given syscall number on the specified [arch].
     * This method is used for union-aware self-verification (issue-20260824-011900).
     *
     * It performs a reverse lookup by iterating through all [Syscall] enum values and finding
     * the one whose syscall number matches [nr] for the given [arch]. If found, returns the
     * corresponding action from [syscallActions] or [defaultAction]. If not found (e.g.,
     * synthetic syscall numbers), returns [defaultAction].
     */
    fun getEffectiveAction(
        nr: Int,
        arch: Arch,
    ): SeccompAction {
        val syscall = if (nr in 0..MAX_SYSCALL_NR) {
            when (arch) {
                Arch.AMD64 -> amd64SyscallTable[nr]
                Arch.AARCH64 -> aarch64SyscallTable[nr]
                else -> Syscall.entries.find { it.numberFor(arch) == nr }
            }
        } else {
            Syscall.entries.find { it.numberFor(arch) == nr }
        }
        return if (syscall != null) {
            syscallActions[syscall] ?: defaultAction
        } else {
            defaultAction
        }
    }

    fun withAppliedFilter(installedFilter: PolicyDefinition<*>): ContainerState {
        val nextDefaultAction = defaultAction.stricter(installedFilter.defaultAction)
        val mergedActions = LinkedHashMap<Syscall, SeccompAction>()
        for (sys in syscallActions.keys + installedFilter.syscallActions.keys) {
            val current = effectiveAction(sys)
            val incoming = installedFilter.syscallActions[sys] ?: installedFilter.defaultAction
            mergedActions[sys] = current.stricter(incoming)
        }

        return copy(
            filterDepth = filterDepth + 1,
            syscallActions = mergedActions.toMap(),
            defaultAction = nextDefaultAction,
            allowsMmapExec = allowsMmapExec && installedFilter.allowMmapExec,
            allowsNonThreadClone = allowsNonThreadClone && installedFilter.allowNonThreadClone,
            allowsUnsafePrctl = allowsUnsafePrctl && installedFilter.allowUnsafePrctl,
        )
    }

    fun withNewSeccompPolicy(
        toInstall: PolicyDefinition<*>,
        newBlocks: Map<Syscall, SeccompAction>,
        newDefaultAction: SeccompAction,
    ): ContainerState {
        val incomingDefault = toInstall.defaultAction.stricter(newDefaultAction)
        val incomingActions = LinkedHashMap(toInstall.syscallActions)
        for ((sys, action) in newBlocks) {
            val existing = incomingActions[sys]
            incomingActions[sys] = existing?.stricter(action) ?: action
        }
        return withAppliedFilter(
            toInstall.copy(
                defaultAction = incomingDefault,
                syscallActions = incomingActions.toMap(),
            ),
        )
    }

    fun withLandlockPolicy(policy: PolicyDefinition<*>): ContainerState =
        copy(
        landlockPolicy = policy,
    )

    fun withEngineState(next: SeccompInstallationState): ContainerState = copy(engineState = next)

    fun withInstallationFailure(failure: Throwable): ContainerState = if (installationFailure == null) copy(installationEvidence = installationEvidence.copy(failure = failure)) else this

    fun withCetProof(proof: CetThreadProof): ContainerState = copy(installationEvidence = installationEvidence.copy(cet = proof))

    fun withRetainedInstallationEvidence(prior: InstallationEvidence): ContainerState =
        copy(installationEvidence = InstallationEvidence(prior.failure ?: installationFailure, prior.cet ?: installationEvidence.cet))

    internal companion object {
        /**
         * Resolves the cumulative security state of the current thread,
         * merging both thread-local and global process-wide restrictions.
         *
         * Thread-Safety and Concurrency:
         * This function reads the thread-local state (`ContainmentStateRegistry.threadState`) and the
         * global process state (`ContainmentStateRegistry.processState`). Because [ContainerState] and
         * its contained collections (e.g. [syscallActions], [allowedSyscalls]) are immutable,
         * and [ContainmentStateRegistry.processState] is managed via an atomic reference, this function
         * is fully thread-safe and robust against concurrent modifications of global policies.
         *
         * However, modifying process-wide global policies concurrently while task executors
         * are active or shutting down is unsupported, as the state resolution of late-running
         * tasks may interleave with global state transitions.
         */
        private const val MAX_SYSCALL_NR = 512
        private val amd64SyscallTable = arrayOfNulls<Syscall>(MAX_SYSCALL_NR + 1).also { table ->
            for (syscall in Syscall.entries) {
                val nr = syscall.numberFor(Arch.AMD64)
                if (nr in 0..MAX_SYSCALL_NR) {
                    table[nr] = syscall
                }
            }
        }
        private val aarch64SyscallTable = arrayOfNulls<Syscall>(MAX_SYSCALL_NR + 1).also { table ->
            for (syscall in Syscall.entries) {
                val nr = syscall.numberFor(Arch.AARCH64)
                if (nr in 0..MAX_SYSCALL_NR) {
                    table[nr] = syscall
                }
            }
        }

        fun resolveCurrentState(): ContainerState {
            return ContainmentStateRegistry.resolveCurrentState()
        }
    }
}
