package io.mazewall.enforcer.engine

import io.mazewall.enforcer.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.PolicyBuilder
import io.mazewall.enforcer.api.PolicyDefinition
import io.mazewall.enforcer.api.PolicyScope
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.state.*
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
import java.util.logging.Logger

/**
 * Internal helper for planning and verifying seccomp filter installations.
 *
 * ARCHITECTURAL INVARIANT: Seccomp BPF filters are additive and intersective.
 * To preserve kernel instruction memory and the 32-filter depth budget, this planner
 * implements Optimized Stacking. It skips compiling duplicate argument-inspection
 * blocks (for mmap, clone, prctl) if those protections are already enforced in
 * the current thread or process state.
 */
internal object FilterInstallationPlanner {
    private val logger = Logger.getLogger(FilterInstallationPlanner::class.java.name)
    private const val MAX_SECCOMP_FILTERS = 32
    private const val WARN_FILTERS_THRESHOLD = 10

    sealed interface PlanDecision {
        data object AlreadySatisfied : PlanDecision

        data class Install(
            val fragment: PolicyDefinition<*>,
            val nextProjection: ContainerState,
            val newBlocks: Map<Syscall, SeccompAction>,
            val newDefaultAction: SeccompAction,
        ) : PlanDecision
    }

    data class FilterPlan(
        val needsNewFilter: Boolean,
        val toInstall: PolicyDefinition<*>,
        val newBlocks: Map<Syscall, SeccompAction>,
        val newDefaultAction: SeccompAction,
    )

    /**
     * Computes whether [policy] adds restrictions beyond [state], and if so binds
     * the minimal BPF [PlanDecision.Install.fragment] to the resulting [PlanDecision.Install.nextProjection].
     *
     * Effective-action projection treats every explicit exception and default action as a
     * single `(defaultAction, syscallActions)` function so allow-list narrowing and stricter
     * defaults with identical allow sets are never skipped as redundant.
     */
    fun plan(
        policy: PolicyDefinition<*>,
        state: ContainerState,
    ): PlanDecision {
        val effectiveNewDefaultAction = state.defaultAction.stricter(policy.defaultAction)
        val needsDefaultActionEscalation = effectiveNewDefaultAction != state.defaultAction

        val newBlocksMap = LinkedHashMap<Syscall, SeccompAction>()
        for (sys in state.syscallActions.keys + policy.syscallActions.keys) {
            val currentAction = state.effectiveAction(sys)
            val incomingAction = policy.syscallActions[sys] ?: policy.defaultAction
            val tightenedAction = currentAction.stricter(incomingAction)
            if (tightenedAction != currentAction) {
                newBlocksMap[sys] = tightenedAction
            }
        }

        val needsMmapProtection = !policy.allowMmapExec && state.allowsMmapExec
        val needsCloneProtection = !policy.allowNonThreadClone && state.allowsNonThreadClone
        val needsPrctlProtection = !policy.allowUnsafePrctl && state.allowsUnsafePrctl

        val needsNewFilter =
            needsDefaultActionEscalation ||
                newBlocksMap.isNotEmpty() ||
                needsMmapProtection ||
                needsCloneProtection ||
                needsPrctlProtection

        if (!needsNewFilter) {
            return PlanDecision.AlreadySatisfied
        }

        val fragment = buildInstallFragment(policy, state, needsDefaultActionEscalation, newBlocksMap)
        return PlanDecision.Install(
            fragment = fragment,
            nextProjection = state.withAppliedFilter(fragment),
            newBlocks = newBlocksMap.toMap(),
            newDefaultAction = effectiveNewDefaultAction,
        )
    }

    private fun buildInstallFragment(
        policy: PolicyDefinition<*>,
        state: ContainerState,
        needsDefaultActionEscalation: Boolean,
        newBlocksMap: LinkedHashMap<Syscall, SeccompAction>,
    ): PolicyDefinition<*> {
        if (needsDefaultActionEscalation) {
            return policy
        }
        val builder = PolicyBuilder<PolicyScope.ThreadLocalOnly>()
        for ((sys, action) in newBlocksMap) {
            builder.addAction(action, sys)
        }
        if (policy.allowMmapExec || !state.allowsMmapExec) builder.allowMmapExec()
        if (policy.allowNonThreadClone || !state.allowsNonThreadClone) builder.allowNonThreadClone()
        if (policy.allowUnsafePrctl || !state.allowsUnsafePrctl) builder.allowUnsafePrctl()
        return builder.build()
    }

    fun calculateNewFilter(
        policy: PolicyDefinition<*>,
        state: ContainerState,
    ): FilterPlan =
        when (val decision = plan(policy, state)) {
            is PlanDecision.AlreadySatisfied ->
                FilterPlan(
                    needsNewFilter = false,
                    toInstall = noopDeltaPolicy(state),
                    newBlocks = emptyMap(),
                    newDefaultAction = state.defaultAction,
                )
            is PlanDecision.Install ->
                FilterPlan(
                    needsNewFilter = true,
                    toInstall = decision.fragment,
                    newBlocks = decision.newBlocks,
                    newDefaultAction = decision.newDefaultAction,
                )
        }

    private fun noopDeltaPolicy(state: ContainerState): PolicyDefinition<PolicyScope.ThreadLocalOnly> {
        val builder = PolicyBuilder<PolicyScope.ThreadLocalOnly>()
        if (!state.allowsMmapExec) builder.allowMmapExec()
        if (!state.allowsNonThreadClone) builder.allowNonThreadClone()
        if (!state.allowsUnsafePrctl) builder.allowUnsafePrctl()
        return builder.build()
    }

    fun verifyFilterDepth(currentDepth: Int) {
        if (currentDepth >= MAX_SECCOMP_FILTERS) {
            throw IllegalStateException("Cannot install more than $MAX_SECCOMP_FILTERS seccomp filters.")
        }
        if (currentDepth > WARN_FILTERS_THRESHOLD) {
            logger.warning("Thread ${Thread.currentThread().name} has $currentDepth seccomp filters.")
        }
    }
}
