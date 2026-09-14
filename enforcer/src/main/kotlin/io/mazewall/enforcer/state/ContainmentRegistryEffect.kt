package io.mazewall.enforcer.state

import io.mazewall.enforcer.api.PolicyDefinition
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
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

    /** Records successful or failed seccomp-engine progress after the corresponding native step. */
    data class EngineStateUpdated(
        val processWide: Boolean,
        val state: SeccompInstallationState,
    ) : ContainmentRegistryEffect
}

internal object ContainmentRegistryEffectInterpreter {
    /** Allocate the new snapshot before restrict_self makes the restriction irreversible. */
    fun prepare(effect: ContainmentRegistryEffect.LandlockApplied): () -> Unit =
        if (effect.processWide) {
            val next = ContainmentStateRegistry.processState.withLandlockPolicy(effect.policy)
            val publish: () -> Unit = { ContainmentStateRegistry.processState = next }
            publish
        } else {
            ContainmentStateRegistry.captureThreadPublication(
                ContainmentStateRegistry.threadState.withLandlockPolicy(effect.policy),
            )
        }

    /** Precompute thread publication before irreversible native installation. */
    fun prepare(effect: ContainmentRegistryEffect.SeccompInstalled): () -> Unit =
        if (effect.processWide) {
            val next = ContainmentStateRegistry.processState.withNewSeccompPolicy(effect.policy, effect.blocks, effect.defaultAction)
            val publish: () -> Unit = { ContainmentStateRegistry.processState = next }
            publish
        } else {
            ContainmentStateRegistry.captureThreadPublication(
                ContainmentStateRegistry.threadState.withNewSeccompPolicy(effect.policy, effect.blocks, effect.defaultAction),
            )
        }

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
