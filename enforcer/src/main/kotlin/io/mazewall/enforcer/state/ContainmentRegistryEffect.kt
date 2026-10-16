package io.mazewall.enforcer.state

import io.mazewall.enforcer.api.PolicyDefinition
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
import io.mazewall.seccomp.SeccompInstallationState

/**
 * Registry updates publish successful irreversible stages or terminal installation failure.
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

    data class InstallationFailed(
        val processWide: Boolean,
        val failure: Throwable,
    ) : ContainmentRegistryEffect

    data class CetLocked(
        val proof: CetThreadProof,
    ) : ContainmentRegistryEffect
}

internal object ContainmentRegistryEffectInterpreter {
    /** Allocate the new snapshot before restrict_self makes the restriction irreversible. */
    fun prepare(effect: ContainmentRegistryEffect.LandlockApplied): () -> Unit =
        if (effect.processWide) {
            val next = ContainmentStateRegistry.processState.withLandlockPolicy(effect.policy)
            val publish: () -> Unit = { publishProcessSnapshot(next) }
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
            val publish: () -> Unit = { publishProcessSnapshot(next) }
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

            is ContainmentRegistryEffect.InstallationFailed ->
                if (effect.processWide) {
                    ContainmentStateRegistry.updateProcessState { it.withInstallationFailure(effect.failure) }
                } else {
                    ContainmentStateRegistry.threadState = ContainmentStateRegistry.threadState.withInstallationFailure(effect.failure)
                }

            is ContainmentRegistryEffect.CetLocked -> {
                check(effect.proof.owner === Thread.currentThread() && !Thread.currentThread().isVirtual) { "CET proof must be published by its owning platform thread" }
                ContainmentStateRegistry.threadState = ContainmentStateRegistry.threadState.withCetProof(effect.proof)
            }
        }
    }

    private fun publishProcessSnapshot(next: ContainerState) {
        ContainmentStateRegistry.updateProcessState { current ->
            val failure = current.installationFailure
            if (failure == null) next else next.withInstallationFailure(failure)
        }
    }
}
