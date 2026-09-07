package io.mazewall.portal.worker

import io.mazewall.portal.PortalFrame
import io.mazewall.portal.PortalKind

/** Pure worker RPC lifecycle; socket I/O and received descriptor ownership stay in the serve loop. */
internal sealed interface PortalWorkerState {
    data object AwaitingRequest : PortalWorkerState

    data class Dispatching(
        val request: PortalFrame,
    ) : PortalWorkerState

    data object Stopped : PortalWorkerState
}

internal sealed interface PortalWorkerEvent {
    data class FrameReceived(
        val frame: PortalFrame,
    ) : PortalWorkerEvent

    data class DispatchSucceeded(
        val payload: ByteArray,
    ) : PortalWorkerEvent

    data class DispatchFailed(
        val message: ByteArray,
    ) : PortalWorkerEvent

    data object IdleTick : PortalWorkerEvent

    data object PeerClosed : PortalWorkerEvent
}

internal sealed interface PortalWorkerEffect {
    data class Dispatch(
        val request: PortalFrame,
    ) : PortalWorkerEffect

    data class Send(
        val frame: PortalFrame,
    ) : PortalWorkerEffect

    data object Drop : PortalWorkerEffect

    data object Await : PortalWorkerEffect

    data object Close : PortalWorkerEffect
}

internal data class PortalWorkerTransition(
    val state: PortalWorkerState,
    val effect: PortalWorkerEffect,
)

internal object PortalWorkerMachine {
    fun evaluate(
        state: PortalWorkerState,
        event: PortalWorkerEvent,
    ): PortalWorkerTransition =
        when (state) {
        PortalWorkerState.AwaitingRequest -> when (event) {
            is PortalWorkerEvent.FrameReceived -> if (event.frame.kind == PortalKind.Request) {
                PortalWorkerTransition(PortalWorkerState.Dispatching(event.frame), PortalWorkerEffect.Dispatch(event.frame))
            } else {
                PortalWorkerTransition(state, PortalWorkerEffect.Drop)
            }
            PortalWorkerEvent.IdleTick -> PortalWorkerTransition(state, PortalWorkerEffect.Await)
            PortalWorkerEvent.PeerClosed -> PortalWorkerTransition(PortalWorkerState.Stopped, PortalWorkerEffect.Close)
            is PortalWorkerEvent.DispatchSucceeded,
            is PortalWorkerEvent.DispatchFailed,
            -> PortalWorkerTransition(state, PortalWorkerEffect.Drop)
        }
        is PortalWorkerState.Dispatching -> when (event) {
            is PortalWorkerEvent.DispatchSucceeded -> reply(state.request, PortalKind.Response, event.payload)
            is PortalWorkerEvent.DispatchFailed -> reply(state.request, PortalKind.Error, event.message)
            PortalWorkerEvent.PeerClosed -> PortalWorkerTransition(PortalWorkerState.Stopped, PortalWorkerEffect.Close)
            is PortalWorkerEvent.FrameReceived,
            PortalWorkerEvent.IdleTick,
            -> PortalWorkerTransition(state, PortalWorkerEffect.Drop)
        }
        PortalWorkerState.Stopped -> PortalWorkerTransition(state, PortalWorkerEffect.Close)
    }

    private fun reply(
        request: PortalFrame,
        kind: PortalKind,
        payload: ByteArray,
    ): PortalWorkerTransition {
        val frame = PortalFrame(kind, request.requestId, request.method, payload, 0)
        return PortalWorkerTransition(PortalWorkerState.AwaitingRequest, PortalWorkerEffect.Send(frame))
    }
}
