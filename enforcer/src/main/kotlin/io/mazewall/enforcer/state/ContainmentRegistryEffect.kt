package io.mazewall.enforcer.state

import io.mazewall.PolicyDefinition
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import io.mazewall.seccomp.SeccompInstallationState

/**
 * Registry updates emitted only after their corresponding irreversible kernel action succeeds.
 * The interpreter is the sole mutation point for [ContainmentStateRegistry].
 */
internal sealed interface ContainmentRegistryEffect {
    data class LandlockApplied(
        val processWide: Boolean,
        val policy: PolicyDefinition<*>,
    ) : ContainmentRegistryEffect

    data class SeccompInstalled(
        val processWide: Boolean,
        val policy: PolicyDefinition<*>,
        val blocks: Map<Syscall, SeccompAction>,
        val defaultAction: SeccompAction,
    ) : ContainmentRegistryEffect

    /** Rollback is valid only for thread-local bookkeeping before Landlock succeeds. */
    data class RestoreThreadState(
        val state: ContainerState,
    ) : ContainmentRegistryEffect

    /** Records successful or failed seccomp-engine progress after the corresponding native step. */
    data class EngineStateUpdated(
        val processWide: Boolean,
        val state: SeccompInstallationState,
    ) : ContainmentRegistryEffect
}

internal object ContainmentRegistryEffectInterpreter {
    fun apply(effect: ContainmentRegistryEffect) {
        when (effect) {
            is ContainmentRegistryEffect.LandlockApplied ->
                if (effect.processWide) {
                    ContainmentStateRegistry.updateProcessState { it.withLandlockPolicy(effect.policy) }
                } else {
                    ContainmentStateRegistry.threadState =
                        ContainmentStateRegistry.threadState.withLandlockPolicy(effect.policy)
                }

            is ContainmentRegistryEffect.SeccompInstalled ->
                if (effect.processWide) {
                    ContainmentStateRegistry.updateProcessState {
                        it.withNewSeccompPolicy(effect.policy, effect.blocks, effect.defaultAction)
                    }
                } else {
                    ContainmentStateRegistry.threadState =
                        ContainmentStateRegistry.threadState.withNewSeccompPolicy(
                            effect.policy,
                            effect.blocks,
                            effect.defaultAction,
                        )
                }

            is ContainmentRegistryEffect.RestoreThreadState -> {
                ContainmentStateRegistry.threadState = effect.state
            }

            is ContainmentRegistryEffect.EngineStateUpdated ->
                if (effect.processWide) {
                    ContainmentStateRegistry.updateProcessState { it.withEngineState(effect.state) }
                    // TSYNC applies the filter to the calling thread as well as every peer thread.
                    ContainmentStateRegistry.threadState =
                        ContainmentStateRegistry.threadState.withEngineState(effect.state)
                } else {
                    ContainmentStateRegistry.threadState =
                        ContainmentStateRegistry.threadState.withEngineState(effect.state)
                }
        }
    }
}
