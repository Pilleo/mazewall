package io.mazewall.enforcer.internal.execution

import io.mazewall.enforcer.api.InstallationEnvironment
import io.mazewall.enforcer.api.execution.PreparedOperationResources
import io.mazewall.enforcer.api.execution.PreparedStackScoping
import io.mazewall.platform.native.Enrollment
import io.mazewall.platform.native.InstallationOwner
import io.mazewall.platform.native.LiveCompletion
import io.mazewall.platform.native.LiveSetup

internal class ScopingPreparation(
    val environment: InstallationEnvironment,
) {
    internal enum class ReleaseStage { INVALIDATE, RETIRE, NATIVE_REFUSED, NATIVE_UNKNOWN, RESOURCES, RESOURCES_UNKNOWN, SETTLED }

    internal enum class ReleasePass { Retained, Releasing }

    internal enum class ReleaseStep { NotAttempted, Complete, Unknown }

    internal enum class ResourceDisposition { Borrowed, AvailableOwned, ClaimedOwned }

    internal sealed interface PrepareRequest {
        data object Awaiting : PrepareRequest

        data class Failed(
            val cause: Throwable,
        ) : PrepareRequest
    }

    internal class ClaimProgress(
        val ready: State.Ready,
        val landlock: io.mazewall.landlock.PreparedLandlock,
        val scoping: FrozenStackScoping.Live,
    ) {
        val observationIdentity = Any()
        private var boundGeneration: Long = 0
        val generation: Long get() = boundGeneration
        val initialState = State.Claiming(this)
        val initialDecision = Decision(initialState, listOf(Effect.SubmitClaim))

        fun bindGeneration(generation: Long) {
            check(boundGeneration == 0L && generation > 0L) { "Claim generation already bound or invalid" }
            boundGeneration = generation
        }
    }

    internal data class Retained(
        val owner: InstallationOwner? = null,
        val enrollment: Enrollment? = null,
        val sealed: InstallationOwner? = null,
        val resources: PreparedOperationResources? = null,
        val frozen: FrozenStackScoping.Live? = null,
        val result: LiveSetup.Ready<PreparedStackScoping>? = null,
        val nativeOutcome: LiveCompletion? = null,
        val step: ReleaseStage = ReleaseStage.INVALIDATE,
        val inFlight: Boolean = false,
        val failure: Throwable? = null,
        val landlock: io.mazewall.landlock.PreparedLandlock? = null,
        val disposition: ResourceDisposition = ResourceDisposition.Borrowed,
        val landlockStep: ReleaseStep = ReleaseStep.NotAttempted,
        val resourceStep: ReleaseStep = ReleaseStep.NotAttempted,
        val observationIdentity: Any? = null,
    )

    internal sealed interface State {
        data class Constructing(
            val retained: Retained = Retained(),
        ) : State

        data class Ready(
            val retained: Retained,
            val frozen: FrozenStackScoping.Live,
            val result: LiveSetup.Ready<PreparedStackScoping>,
        ) : State

        data class Claiming(
            val acquisition: ClaimProgress,
            val request: PrepareRequest = PrepareRequest.Awaiting,
            val disposition: ResourceDisposition = ResourceDisposition.AvailableOwned,
            val history: Throwable? = null,
        ) : State

        data class DomainOwned(
            val domain: OwnedDomain,
            val history: Throwable? = null,
        ) : State

        data class TransferredSettled(
            val domain: OwnedDomain,
            val history: Throwable? = null,
        ) : State

        data class Retiring(
            val retained: Retained,
        ) : State

        data class PreparedSettled(
            val failure: Throwable?,
            val observationIdentity: Any? = null,
        ) : State
    }

    internal sealed interface ObservationOrigin {
        data class Claim(
            val identity: Any,
        ) : ObservationOrigin

        data class Domain(
            val domain: OwnedDomain,
        ) : ObservationOrigin
    }

    internal sealed interface WaitObservation {
        data class Initial(
            val cause: Throwable,
        ) : WaitObservation

        data class HistoryObservation(
            val original: Throwable,
            val merged: Throwable,
            val origin: ObservationOrigin,
        ) : WaitObservation
    }

    internal sealed interface Event {
        data class Reserved(
            val owner: InstallationOwner,
        ) : Event

        data class Enrolled(
            val enrollment: Enrollment,
        ) : Event

        data class Sealed(
            val owner: InstallationOwner,
            val frozen: FrozenStackScoping.Live? = null,
            val result: LiveSetup.Ready<PreparedStackScoping>? = null,
        ) : Event

        data class Publish(
            val next: State.Ready,
        ) : Event

        data class ConstructionFailed(
            val cause: Throwable?,
        ) : Event

        data class ClaimRequested(
            val runtime: io.mazewall.enforcer.api.execution.ManagedRuntime,
            val policy: io.mazewall.enforcer.api.PolicyDefinition<*>,
            val limits: io.mazewall.enforcer.api.execution.DomainLimits,
            val native: io.mazewall.platform.native.RuntimeSnapshot,
            val runtimeOpen: Boolean,
            val generation: Long,
            val landlock: io.mazewall.landlock.PreparedLandlock,
            val scoping: FrozenStackScoping.Live,
            val carrier: ClaimProgress,
        ) : Event

        data object ResourcesClaimed : Event

        data class LandlockPrepared(
            val owner: io.mazewall.landlock.PreparedLandlock,
        ) : Event

        data class PrepareWaitFailed(
            val observation: WaitObservation,
        ) : Event

        data class DomainCommit(
            val domain: OwnedDomain,
            val runtimeOpen: Boolean,
        ) : Event {
            val decision = Decision(State.DomainOwned(domain), listOf(Effect.Notify))
        }

        data class ClaimFailed(
            val cause: Throwable,
        ) : Event

        data class DomainSettled(
            val domain: OwnedDomain,
            val history: Throwable?,
        ) : Event

        data object CloseRequested : Event

        data object DrainRequested : Event

        data object ReleaseRequested : Event

        data class ReleaseCompleted(
            val retained: Retained,
        ) : Event
    }

    internal sealed interface Effect {
        data object Notify : Effect

        data object SubmitClaim : Effect

        data object ObserveClaim : Effect

        data object ObserveDomain : Effect

        data class FailDomain(
            val domain: OwnedDomain,
            val cause: Throwable,
        ) : Effect

        data object SubmitRelease : Effect

        data class Reject(
            val cause: Throwable,
        ) : Effect
    }

    internal data class Decision(
        val state: State,
        val effects: List<Effect> = emptyList(),
    )

    internal val constructionFinished = java.util.concurrent.CompletableFuture<Unit>()
    internal val claimFinished = java.util.concurrent.CompletableFuture<Unit>()
    internal var state: State = State.Constructing()

    internal companion object {
        fun evaluate(
            state: State,
            event: Event,
        ): Decision =
            when (event) {
                is Event.Reserved -> reserved(state, event)
                is Event.Enrolled -> enrolled(state, event)
                is Event.Sealed -> sealed(state, event)
                is Event.Publish -> publish(state, event)
                is Event.ConstructionFailed -> constructionFailed(state, event)
                Event.CloseRequested, Event.DrainRequested -> closeOrDrain(state)
                Event.ReleaseRequested -> releaseRequested(state)
                is Event.ReleaseCompleted -> releaseCompleted(state, event)
                is Event.ClaimRequested, Event.ResourcesClaimed, is Event.LandlockPrepared,
                is Event.PrepareWaitFailed, is Event.DomainCommit, is Event.ClaimFailed,
                is Event.DomainSettled,
                -> evaluateClaim(state, event)
            }

        private fun evaluateClaim(
            state: State,
            event: Event,
        ): Decision =
            when (event) {
                is Event.ClaimRequested -> claimRequested(state, event)
                Event.ResourcesClaimed -> resourcesClaimed(state)
                is Event.LandlockPrepared -> landlockPrepared(state, event)
                is Event.PrepareWaitFailed -> prepareWaitFailed(state, event)
                is Event.DomainCommit -> domainCommit(state, event)
                is Event.ClaimFailed -> claimFailed(state, event)
                is Event.DomainSettled -> domainSettled(state, event)
                is Event.Reserved, is Event.Enrolled, is Event.Sealed, is Event.Publish,
                is Event.ConstructionFailed, Event.CloseRequested, Event.DrainRequested,
                Event.ReleaseRequested, is Event.ReleaseCompleted,
                -> reject(state)
            }

        private fun reserved(
            state: State,
            event: Event.Reserved,
        ): Decision =
            when (state) {
                is State.Constructing -> if (state.retained.owner == null || state.retained.owner === event.owner) {
                    Decision(State.Constructing(state.retained.copy(owner = event.owner)))
                } else {
                    reject(state)
                }
                is State.Ready, is State.Retiring, is State.PreparedSettled, is State.Claiming, is State.DomainOwned, is State.TransferredSettled -> reject(state)
            }

        private fun enrolled(
            state: State,
            event: Event.Enrolled,
        ): Decision =
            when (state) {
                is State.Constructing -> if (state.retained.owner === event.enrollment.owner) {
                    Decision(State.Constructing(state.retained.copy(enrollment = event.enrollment)))
                } else {
                    reject(state)
                }
                is State.Ready, is State.Retiring, is State.PreparedSettled, is State.Claiming, is State.DomainOwned, is State.TransferredSettled -> reject(state)
            }

        private fun sealed(
            state: State,
            event: Event.Sealed,
        ): Decision =
            when (state) {
                is State.Constructing -> if (sealedMatches(state.retained, event.owner)) {
                    if ((event.frozen == null) != (event.result == null) ||
                        (event.frozen != null && state.retained.sealed !== event.owner)
                    ) {
                        reject(state)
                    } else {
                        Decision(
                            State.Constructing(
                                state.retained.copy(
                                    sealed = event.owner,
                                    frozen = event.frozen ?: state.retained.frozen,
                                    result =
                        event.result ?: state.retained.result,
                                ),
                            ),
                        )
                    }
                } else {
                    reject(state)
                }
                is State.Ready, is State.Retiring, is State.PreparedSettled, is State.Claiming, is State.DomainOwned, is State.TransferredSettled -> reject(state)
            }

        private fun publish(
            state: State,
            event: Event.Publish,
        ): Decision =
            when (state) {
                is State.Constructing -> if (event.next.retained.owner === state.retained.owner &&
                    event.next.retained.enrollment === state.retained.enrollment &&
                    event.next.retained.sealed === state.retained.sealed &&
                    event.next.frozen === state.retained.frozen &&
                    event.next.result === state.retained.result
                ) {
                        Decision(event.next)
                    } else {
                        reject(state)
                    }
                is State.Ready, is State.Retiring, is State.PreparedSettled, is State.Claiming, is State.DomainOwned, is State.TransferredSettled -> reject(state)
            }

        private fun constructionFailed(
            state: State,
            event: Event.ConstructionFailed,
        ): Decision =
            when (state) {
                is State.Constructing -> if (state.retained.owner ==
                    null
                ) {
                        Decision(State.PreparedSettled(event.cause), listOf(Effect.Notify))
                    } else {
                        Decision(State.Retiring(state.retained.copy(failure = event.cause)), listOf(Effect.Notify))
                    }
                is State.Ready, is State.Retiring, is State.PreparedSettled, is State.Claiming, is State.DomainOwned, is State.TransferredSettled -> reject(state)
            }

        private fun closeOrDrain(state: State): Decision =
            when (state) {
                is State.Constructing -> Decision(state)
                is State.Ready -> Decision(State.Retiring(state.retained))
                is State.Claiming -> Decision(state, listOf(Effect.ObserveClaim))
                is State.DomainOwned -> Decision(state, listOf(Effect.ObserveDomain))
                is State.Retiring, is State.PreparedSettled, is State.TransferredSettled -> Decision(state)
            }

        private fun releaseRequested(state: State): Decision =
            when (state) {
                is State.Retiring -> if (!state.retained.inFlight) Decision(State.Retiring(state.retained.copy(inFlight = true)), listOf(Effect.SubmitRelease)) else Decision(state)
                is State.Constructing, is State.Ready, is State.PreparedSettled, is State.Claiming, is State.DomainOwned, is State.TransferredSettled -> reject(state)
            }

        private fun releaseCompleted(
            state: State,
            event: Event.ReleaseCompleted,
        ): Decision =
            when (state) {
                is State.Retiring -> if (state.retained.inFlight &&
                    event.retained.owner === state.retained.owner &&
                    event.retained.landlock === state.retained.landlock &&
                    event.retained.observationIdentity === state.retained.observationIdentity
                ) {
                    val history = event.retained.failure?.let {
                        io.mazewall.platform.fd.CleanupFailures
                            .merge(state.retained.failure, it)
                    } ?: state.retained.failure
                    if (event.retained.step == ReleaseStage.SETTLED) {
                        Decision(State.PreparedSettled(history, event.retained.observationIdentity), listOf(Effect.Notify))
                    } else {
                        Decision(State.Retiring(event.retained.copy(inFlight = false, failure = history)), listOf(Effect.Notify))
                    }
                } else {
                    reject(state)
                }
                is State.Constructing, is State.Ready, is State.PreparedSettled, is State.Claiming, is State.DomainOwned, is State.TransferredSettled -> reject(state)
            }

        private fun claimRequested(
            state: State,
            event: Event.ClaimRequested,
        ): Decision {
            val carrier = event.carrier
            if (state !is State.Ready ||
                !event.runtimeOpen ||
                event.generation <= 0 ||
                !matchesCarrier(state, event) ||
                state.retained.disposition != ResourceDisposition.AvailableOwned ||
                !matchesClaimInputs(state, event)
            ) {
                return reject(state)
            }
            return carrier.initialDecision
        }

        private fun matchesClaimInputs(
            state: State.Ready,
            event: Event.ClaimRequested,
        ): Boolean =
            state.frozen.bootstrap.matchesClaim(event.runtime, event.policy, event.limits, event.native) &&
                state.frozen.bootstrap.ownsRetainedShell(event.landlock)

        private fun matchesCarrier(
            state: State.Ready,
            event: Event.ClaimRequested,
        ): Boolean =
            event.carrier.ready === state &&
                event.carrier.generation == event.generation &&
                event.carrier.scoping === event.scoping &&
                event.carrier.landlock === event.landlock &&
                event.scoping === state.frozen

        private fun resourcesClaimed(state: State): Decision =
            if (state is State.Claiming && state.disposition == ResourceDisposition.AvailableOwned) {
                Decision(state.copy(disposition = ResourceDisposition.ClaimedOwned))
            } else {
                reject(state)
            }

        private fun landlockPrepared(
            state: State,
            event: Event.LandlockPrepared,
        ): Decision = if (state is State.Claiming && state.acquisition.landlock === event.owner) Decision(state) else reject(state)

        fun observationOrigin(state: State): ObservationOrigin =
            when (state) {
                is State.Claiming -> ObservationOrigin.Claim(state.acquisition.observationIdentity)
                is State.Retiring -> ObservationOrigin.Claim(checkNotNull(state.retained.observationIdentity))
                is State.PreparedSettled -> ObservationOrigin.Claim(checkNotNull(state.observationIdentity))
                is State.DomainOwned -> ObservationOrigin.Domain(state.domain)
                is State.TransferredSettled -> ObservationOrigin.Domain(state.domain)
                is State.Constructing, is State.Ready -> error("No admitted claim for wait observation")
            }

        private fun prepareWaitFailed(
            state: State,
            event: Event.PrepareWaitFailed,
        ): Decision =
            when (val observation = event.observation) {
                is WaitObservation.Initial -> initialWaitFailure(state, observation.cause)
                is WaitObservation.HistoryObservation -> observeWaitHistory(state, observation)
            }

        private fun initialWaitFailure(
            state: State,
            cause: Throwable,
        ): Decision =
            when (state) {
                is State.Claiming -> if (state.request is PrepareRequest.Awaiting) {
                    Decision(state.copy(request = PrepareRequest.Failed(cause), history = cause))
                } else {
                    Decision(state)
                }
                is State.DomainOwned -> if (containsFailure(state.history, cause)) {
                    Decision(state)
                } else {
                    Decision(
                        state.copy(
                            history = io.mazewall.platform.fd.CleanupFailures
                        .merge(state.history, cause),
                        ),
                            listOf(Effect.FailDomain(state.domain, cause)),
                    )
                }
                is State.TransferredSettled -> if (containsFailure(state.history, cause)) {
                    Decision(state)
                } else {
                    Decision(
                        state.copy(
                            history = io.mazewall.platform.fd.CleanupFailures
                        .merge(state.history, cause),
                        ),
                            listOf(Effect.FailDomain(state.domain, cause)),
                    )
                }
                is State.Retiring -> Decision(
                    State.Retiring(
                        state.retained.copy(
                            failure = io.mazewall.platform.fd.CleanupFailures
                    .merge(state.retained.failure, cause),
                        ),
                    ),
                )
                is State.PreparedSettled -> Decision(
                    state.copy(
                        failure = io.mazewall.platform.fd.CleanupFailures
                    .merge(state.failure, cause),
                    ),
                )
                is State.Constructing, is State.Ready -> reject(state)
            }

        private fun observeWaitHistory(
            state: State,
            observation: WaitObservation.HistoryObservation,
        ): Decision {
            if (!matchesObservation(state, observation)) return reject(state)
            val merged = observation.merged
            return when (state) {
                is State.Claiming -> Decision(
                    state.copy(
                        history = io.mazewall.platform.fd.CleanupFailures
                    .merge(state.history, merged),
                    ),
                )
                is State.Retiring -> Decision(
                    State.Retiring(
                        state.retained.copy(
                            failure = io.mazewall.platform.fd.CleanupFailures
                    .merge(state.retained.failure, merged),
                        ),
                    ),
                )
                is State.PreparedSettled -> Decision(
                    state.copy(
                        failure = io.mazewall.platform.fd.CleanupFailures
                    .merge(state.failure, merged),
                    ),
                )
                is State.DomainOwned -> Decision(
                    state.copy(
                        history = io.mazewall.platform.fd.CleanupFailures
                    .merge(state.history, merged),
                    ),
                )
                is State.TransferredSettled -> Decision(
                    state.copy(
                        history = io.mazewall.platform.fd.CleanupFailures
                    .merge(state.history, merged),
                    ),
                )
                is State.Constructing, is State.Ready -> reject(state)
            }
        }

        private fun matchesObservation(
            state: State,
            observation: WaitObservation.HistoryObservation,
        ): Boolean {
            val history = observationHistory(state)
            val exact = when (val origin = observation.origin) {
                is ObservationOrigin.Claim -> when (state) {
                    is State.Claiming -> state.acquisition.observationIdentity === origin.identity
                    is State.Retiring -> state.retained.observationIdentity === origin.identity
                    is State.PreparedSettled -> state.observationIdentity === origin.identity
                    is State.Constructing, is State.Ready, is State.DomainOwned, is State.TransferredSettled -> false
                }
                is ObservationOrigin.Domain -> when (state) {
                    is State.DomainOwned -> state.domain === origin.domain
                    is State.TransferredSettled -> state.domain === origin.domain
                    is State.Constructing, is State.Ready, is State.Claiming, is State.Retiring, is State.PreparedSettled -> false
                }
            }
            return exact && containsFailure(history, observation.original)
        }

        private fun observationHistory(state: State): Throwable? =
            when (state) {
                is State.Claiming -> state.history
                is State.Retiring -> state.retained.failure
                is State.PreparedSettled -> state.failure
                is State.DomainOwned -> state.history
                is State.TransferredSettled -> state.history
                is State.Constructing, is State.Ready -> null
            }

        private fun domainCommit(
            state: State,
            event: Event.DomainCommit,
        ): Decision =
            if (state is State.Claiming &&
                event.runtimeOpen &&
                state.request is PrepareRequest.Awaiting &&
                state.disposition == ResourceDisposition.ClaimedOwned &&
                event.domain.generation == state.acquisition.generation &&
                event.domain.ownsScoping(state.acquisition.scoping)
            ) {
                event.decision
            } else {
                reject(state)
            }

        private fun claimFailed(
            state: State,
            event: Event.ClaimFailed,
        ): Decision {
            if (state !is State.Claiming) return reject(state)
            val claim = state.acquisition
            val waitFailure = state.history ?: (state.request as? PrepareRequest.Failed)?.cause
            val cause = io.mazewall.platform.fd.CleanupFailures
                .merge(waitFailure, event.cause)
            return Decision(
                State.Retiring(claim.ready.retained.copy(observationIdentity = claim.observationIdentity, landlock = claim.landlock, disposition = state.disposition, failure = cause)),
                listOf(Effect.Notify),
            )
        }

        private fun domainSettled(
            state: State,
            event: Event.DomainSettled,
        ): Decision =
            when (state) {
            is State.DomainOwned -> if (state.domain === event.domain) {
                Decision(
                State.TransferredSettled(
                    state.domain,
                    event.history?.let {
                    io.mazewall.platform.fd.CleanupFailures
                    .merge(state.history, it)
                } ?: state.history,
                ),
            )
            } else {
                reject(state)
            }
            is State.TransferredSettled -> if (state.domain === event.domain) Decision(state) else reject(state)
            is State.Constructing, is State.Ready, is State.Claiming,
            is State.Retiring, is State.PreparedSettled,
            -> reject(state)
        }

        private fun sealedMatches(
            retained: Retained,
            sealed: InstallationOwner,
        ): Boolean {
            val owner = retained.owner ?: return false
            val enrollment = retained.enrollment ?: return false
            val sealedEnrollment = (sealed.state as? io.mazewall.platform.native.InstallationState.Sealed)?.enrollment
            return sealedEnrollment === enrollment &&
                sealed.capability === owner.capability &&
                sealed.process == owner.process &&
                sealed.installation == owner.installation &&
                sealed.capacity == owner.capacity
        }

        private fun reject(state: State) = Decision(state, listOf(Effect.Reject(IllegalStateException("Invalid scoping transition"))))
    }
}

private fun containsFailure(
    root: Throwable?,
    target: Throwable,
): Boolean {
    if (root == null) return false
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    val pending = java.util.ArrayDeque<Throwable>()
    pending.add(root)
    while (pending.isNotEmpty()) {
        val current = pending.removeLast()
        if (current === target) return true
        if (seen.add(current)) {
            current.suppressed.forEach(pending::add)
        }
    }
    return false
}
