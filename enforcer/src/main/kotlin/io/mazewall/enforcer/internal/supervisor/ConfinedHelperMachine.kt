package io.mazewall.enforcer.internal.supervisor

import io.mazewall.ffi.networking.ConfinedDeadline

private const val MAX_LINUX_ERRNO = 4095

internal enum class ConfinedTransfer { AwaitOffer, AwaitExpected, AwaitDescriptor, AwaitReceipt, AwaitDone }

internal enum class ConfinedSetupPhase { Validating, Roots }

internal data class ConfinedActiveRead(
    val request: ConfinedReadRequest,
    val deadline: ConfinedDeadline,
    val phase: ConfinedTransfer,
)

/** Metadata only. The interpreter, never these states or effects, owns descriptors. */
internal sealed interface ConfinedHelperState {
    data object Unsealed : ConfinedHelperState

    data class Receiving(
        val manifest: ConfinedManifest,
        val deadline: ConfinedDeadline,
        val nextIndex: Int = 0,
        val setup: ConfinedSetupPhase = ConfinedSetupPhase.Validating,
        val phase: ConfinedTransfer = ConfinedTransfer.AwaitOffer,
    ) : ConfinedHelperState

    data class Confining(
        val manifest: ConfinedManifest,
        val deadline: ConfinedDeadline,
    ) : ConfinedHelperState

    data class Ready(
        val manifest: ConfinedManifest,
        val lastRequest: Long = 0,
        val active: ConfinedActiveRead? = null,
    ) : ConfinedHelperState

    data class Stopping(
        val binding: ConfinedBinding,
        val deadline: ConfinedDeadline,
        val reason: StopReason,
    ) : ConfinedHelperState

    data class Terminal(
        val cause: Throwable?,
    ) : ConfinedHelperState
}

internal sealed interface ConfinedHelperEvent {
    data class ParentFrame(
        val frame: ConfinedHelperProtocol.Frame,
    ) : ConfinedHelperEvent

    data class SetupValidated(
        val binding: ConfinedBinding,
        val deadline: ConfinedDeadline,
    ) : ConfinedHelperEvent

    data class RootAdopted(
        val binding: ConfinedBinding,
        val index: Int,
        val deadline: ConfinedDeadline,
    ) : ConfinedHelperEvent

    data class Sealed(
        val binding: ConfinedBinding,
        val deadline: ConfinedDeadline,
    ) : ConfinedHelperEvent

    data class Opened(
        val binding: ConfinedBinding,
        val id: Long,
        val deadline: ConfinedDeadline,
    ) : ConfinedHelperEvent

    data class Denied(
        val binding: ConfinedBinding,
        val id: Long,
        val errno: Int,
        val deadline: ConfinedDeadline,
    ) : ConfinedHelperEvent

    data class ResultSent(
        val binding: ConfinedBinding,
        val id: Long,
        val deadline: ConfinedDeadline,
    ) : ConfinedHelperEvent

    data class ReadCompleted(
        val binding: ConfinedBinding,
        val id: Long,
        val deadline: ConfinedDeadline,
    ) : ConfinedHelperEvent

    data class Stopped(
        val binding: ConfinedBinding,
        val deadline: ConfinedDeadline,
    ) : ConfinedHelperEvent

    data class Cancel(
        val cause: Throwable,
    ) : ConfinedHelperEvent
}

internal sealed interface ConfinedHelperEffect {
    data class Send(
        val frame: ConfinedHelperProtocol.Frame,
    ) : ConfinedHelperEffect

    data class Validate(
        val manifest: ConfinedManifest,
        val deadline: ConfinedDeadline,
    ) : ConfinedHelperEffect

    data class ReceiveRoot(
        val capability: HelperCapability,
        val deadline: ConfinedDeadline,
    ) : ConfinedHelperEffect

    data class Seal(
        val manifest: ConfinedManifest,
        val deadline: ConfinedDeadline,
    ) : ConfinedHelperEffect

    data class Open(
        val request: ConfinedReadRequest,
        val deadline: ConfinedDeadline,
    ) : ConfinedHelperEffect

    data class SendResult(
        val id: Long,
        val deadline: ConfinedDeadline,
    ) : ConfinedHelperEffect

    data class CommitResult(
        val id: Long,
        val deadline: ConfinedDeadline,
    ) : ConfinedHelperEffect

    data class Stop(
        val reason: StopReason,
        val deadline: ConfinedDeadline,
    ) : ConfinedHelperEffect

    data class Abort(
        val cause: Throwable,
    ) : ConfinedHelperEffect
}

/** The helper and staged relay consume the same receiver transitions and acknowledge real effects. */
internal object ConfinedHelperMachine {
    data class Transition(
        val state: ConfinedHelperState,
        val effects: List<ConfinedHelperEffect>,
    )

    fun evaluate(
        state: ConfinedHelperState,
        event: ConfinedHelperEvent,
    ): Transition {
        if (state is ConfinedHelperState.Terminal) return Transition(state, emptyList())
        return when (event) {
            is ConfinedHelperEvent.ParentFrame -> parentFrame(state, event.frame)
            is ConfinedHelperEvent.Cancel -> abort(event.cause)
            is ConfinedHelperEvent.SetupValidated -> setupValidated(state, event)
            is ConfinedHelperEvent.RootAdopted -> rootAdopted(state, event)
            is ConfinedHelperEvent.Sealed -> sealedTransition(state, event)
            is ConfinedHelperEvent.Opened -> withRead(state, event.binding, event.id, event.deadline, ConfinedTransfer.AwaitOffer) { ready, active ->
                Transition(
                    ready.copy(active = active.copy(phase = ConfinedTransfer.AwaitExpected)),
                    listOf(send(ConfinedHelperProtocol.Frame.Result(ConfinedHelperProtocol.ResultTag.Opened, event.binding, event.id, event.deadline))),
                )
            }
            is ConfinedHelperEvent.Denied -> withRead(state, event.binding, event.id, event.deadline, ConfinedTransfer.AwaitOffer) { ready, _ ->
                if (event.errno !in 1..MAX_LINUX_ERRNO) {
                    reject()
                } else {
                    Transition(ready.copy(active = null), listOf(send(ConfinedHelperProtocol.Frame.Denied(event.binding, event.id, event.errno, event.deadline))))
                }
            }
            is ConfinedHelperEvent.ResultSent -> withRead(state, event.binding, event.id, event.deadline, ConfinedTransfer.AwaitDescriptor) { ready, active ->
                Transition(ready.copy(active = active.copy(phase = ConfinedTransfer.AwaitReceipt)), emptyList())
            }
            is ConfinedHelperEvent.ReadCompleted -> withRead(state, event.binding, event.id, event.deadline, ConfinedTransfer.AwaitDone) { ready, _ ->
                Transition(ready.copy(active = null), listOf(send(ConfinedHelperProtocol.Frame.Result(ConfinedHelperProtocol.ResultTag.Done, event.binding, event.id, event.deadline))))
            }
            is ConfinedHelperEvent.Stopped -> stopped(state, event)
        }
    }

    private fun sealedTransition(
        state: ConfinedHelperState,
        event: ConfinedHelperEvent.Sealed,
    ): Transition =
        if (state !is ConfinedHelperState.Confining || state.manifest.binding != event.binding || state.deadline != event.deadline) {
            reject()
        } else {
            Transition(ConfinedHelperState.Ready(state.manifest), listOf(send(ConfinedHelperProtocol.Frame.Control(ConfinedHelperProtocol.ControlTag.Ready, event.binding, event.deadline))))
        }

    private fun stopped(
        state: ConfinedHelperState,
        event: ConfinedHelperEvent.Stopped,
    ): Transition =
        if (state !is ConfinedHelperState.Stopping || state.binding != event.binding || state.deadline != event.deadline) {
            reject()
        } else {
            Transition(
                ConfinedHelperState.Terminal(null),
                listOf(send(ConfinedHelperProtocol.Frame.Stop(ConfinedHelperProtocol.StopTag.LocalStopped, event.binding, state.reason, event.deadline))),
            )
        }

    private fun setupValidated(
        state: ConfinedHelperState,
        event: ConfinedHelperEvent.SetupValidated,
    ): Transition {
        if (state !is ConfinedHelperState.Receiving ||
            state.setup != ConfinedSetupPhase.Validating ||
            state.manifest.binding != event.binding ||
            state.deadline != event.deadline
        ) {
            return reject()
        }
        return Transition(
            state.copy(setup = ConfinedSetupPhase.Roots),
            listOf(send(ConfinedHelperProtocol.Frame.Control(ConfinedHelperProtocol.ControlTag.SetupAccepted, event.binding, event.deadline))),
        )
    }

    private fun rootAdopted(
        state: ConfinedHelperState,
        event: ConfinedHelperEvent.RootAdopted,
    ): Transition {
        if (state !is ConfinedHelperState.Receiving ||
            state.setup != ConfinedSetupPhase.Roots ||
            state.phase != ConfinedTransfer.AwaitDescriptor ||
            state.nextIndex != event.index ||
            state.manifest.binding != event.binding ||
            state.deadline != event.deadline
        ) {
            return reject()
        }
        return Transition(
            state.copy(nextIndex = state.nextIndex + 1, phase = ConfinedTransfer.AwaitOffer),
            listOf(send(ConfinedHelperProtocol.Frame.Root(ConfinedHelperProtocol.RootTag.Received, event.binding, event.index, event.deadline))),
        )
    }

    private fun parentFrame(
        state: ConfinedHelperState,
        frame: ConfinedHelperProtocol.Frame,
    ): Transition =
        when (frame) {
        is ConfinedHelperProtocol.Frame.Setup -> {
            if (state != ConfinedHelperState.Unsealed) {
                reject()
            } else {
                Transition(
                ConfinedHelperState.Receiving(frame.manifest, frame.deadline),
                    listOf(ConfinedHelperEffect.Validate(frame.manifest, frame.deadline)),
            )
            }
        }
        is ConfinedHelperProtocol.Frame.Root -> root(state, frame)
        is ConfinedHelperProtocol.Frame.Read -> readFrame(state, frame)
        is ConfinedHelperProtocol.Frame.Result -> when (frame.tag) {
            ConfinedHelperProtocol.ResultTag.Expected -> withRead(state, frame.binding, frame.id, frame.deadline, ConfinedTransfer.AwaitExpected) { ready, active ->
                Transition(ready.copy(active = active.copy(phase = ConfinedTransfer.AwaitDescriptor)), listOf(ConfinedHelperEffect.SendResult(frame.id, frame.deadline)))
            }
            ConfinedHelperProtocol.ResultTag.Received -> withRead(state, frame.binding, frame.id, frame.deadline, ConfinedTransfer.AwaitReceipt) { ready, active ->
                Transition(ready.copy(active = active.copy(phase = ConfinedTransfer.AwaitDone)), listOf(ConfinedHelperEffect.CommitResult(frame.id, frame.deadline)))
            }
            ConfinedHelperProtocol.ResultTag.Opened, ConfinedHelperProtocol.ResultTag.Done -> reject()
        }
        is ConfinedHelperProtocol.Frame.Stop -> stop(state, frame)
        is ConfinedHelperProtocol.Frame.Control, is ConfinedHelperProtocol.Frame.Denied,
        is ConfinedHelperProtocol.Frame.Rejected, is ConfinedHelperProtocol.Frame.Retired,
        -> reject()
    }

    private fun readFrame(
        state: ConfinedHelperState,
        frame: ConfinedHelperProtocol.Frame.Read,
    ): Transition =
        if (state !is ConfinedHelperState.Ready ||
            state.active != null ||
            state.manifest.binding != frame.binding ||
            frame.request.id <= state.lastRequest ||
            state.manifest.capabilities
                .getOrNull(frame.request.index)
                ?.purpose != frame.request.purpose
        ) {
            reject()
        } else {
            Transition(
                state.copy(lastRequest = frame.request.id, active = ConfinedActiveRead(frame.request, frame.deadline, ConfinedTransfer.AwaitOffer)),
                listOf(ConfinedHelperEffect.Open(frame.request, frame.deadline)),
            )
        }

    private fun root(
        state: ConfinedHelperState,
        frame: ConfinedHelperProtocol.Frame.Root,
    ): Transition {
        if (state !is ConfinedHelperState.Receiving ||
            state.setup != ConfinedSetupPhase.Roots ||
            state.phase != ConfinedTransfer.AwaitOffer ||
            state.manifest.binding != frame.binding ||
            state.deadline != frame.deadline ||
            frame.index != state.nextIndex
        ) {
            return reject()
        }
        return when (frame.tag) {
            ConfinedHelperProtocol.RootTag.Offer -> {
                val capability = state.manifest.capabilities.getOrNull(frame.index) ?: return reject()
                Transition(
                    state.copy(phase = ConfinedTransfer.AwaitDescriptor),
                    listOf(
                    send(ConfinedHelperProtocol.Frame.Root(ConfinedHelperProtocol.RootTag.Expected, frame.binding, frame.index, frame.deadline)),
                    ConfinedHelperEffect.ReceiveRoot(capability, frame.deadline),
                ),
                )
            }
            ConfinedHelperProtocol.RootTag.Complete -> {
                if (frame.index != state.manifest.capabilities.size) {
                    reject()
                } else {
                    Transition(
                    ConfinedHelperState.Confining(state.manifest, frame.deadline),
                        listOf(ConfinedHelperEffect.Seal(state.manifest, frame.deadline)),
                )
                }
            }
            ConfinedHelperProtocol.RootTag.Expected, ConfinedHelperProtocol.RootTag.Received -> reject()
        }
    }

    private fun stop(
        state: ConfinedHelperState,
        frame: ConfinedHelperProtocol.Frame.Stop,
    ): Transition {
        if (frame.tag != ConfinedHelperProtocol.StopTag.Stop) return reject()
        val binding = when (state) {
            is ConfinedHelperState.Receiving -> if (state.phase == ConfinedTransfer.AwaitOffer) state.manifest.binding else null
            is ConfinedHelperState.Confining -> state.manifest.binding
            is ConfinedHelperState.Ready -> if (state.active == null) state.manifest.binding else null
            ConfinedHelperState.Unsealed, is ConfinedHelperState.Stopping, is ConfinedHelperState.Terminal -> null
        }
        if (binding != frame.binding) return reject()
        return Transition(ConfinedHelperState.Stopping(frame.binding, frame.deadline, frame.reason), listOf(ConfinedHelperEffect.Stop(frame.reason, frame.deadline)))
    }

    private inline fun withRead(
        state: ConfinedHelperState,
        binding: ConfinedBinding,
        id: Long,
        deadline: ConfinedDeadline,
        phase: ConfinedTransfer,
        action: (ConfinedHelperState.Ready, ConfinedActiveRead) -> Transition,
    ): Transition {
        val active = (state as? ConfinedHelperState.Ready)?.active
        return if (state !is ConfinedHelperState.Ready || active == null || state.manifest.binding != binding || active.request.id != id || active.deadline != deadline || active.phase != phase) {
            reject()
        } else {
            action(state, active)
        }
    }

    private fun send(frame: ConfinedHelperProtocol.Frame): ConfinedHelperEffect = ConfinedHelperEffect.Send(frame)

    private fun reject(): Transition = abort(IllegalStateException("Unexpected confined phase or identity"))

    private fun abort(cause: Throwable): Transition = Transition(ConfinedHelperState.Terminal(cause), listOf(ConfinedHelperEffect.Abort(cause)))
}
