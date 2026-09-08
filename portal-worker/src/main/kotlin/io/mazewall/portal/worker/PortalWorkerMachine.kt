package io.mazewall.portal.worker

import io.mazewall.portal.PortalFrame
import io.mazewall.portal.PortalKind
import io.mazewall.portal.PortalPayload

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
        val payload: PortalPayload,
    ) : PortalWorkerEvent

    data class DispatchFailed(
        val message: PortalPayload,
    ) : PortalWorkerEvent

    data object IdleTick : PortalWorkerEvent

    data object PeerClosed : PortalWorkerEvent
}

internal sealed interface PortalWorkerTransition {
    val state: PortalWorkerState

    data class Dispatch(
        override val state: PortalWorkerState.Dispatching,
        val request: PortalFrame,
    ) : PortalWorkerTransition

    data class Send(
        override val state: PortalWorkerState,
        val frame: PortalFrame,
    ) : PortalWorkerTransition

    data class Drop(
        override val state: PortalWorkerState,
    ) : PortalWorkerTransition

    data class Await(
        override val state: PortalWorkerState,
    ) : PortalWorkerTransition

    data class Close(
        override val state: PortalWorkerState.Stopped,
    ) : PortalWorkerTransition
}

internal object PortalWorkerMachine {
    fun evaluate(
        state: PortalWorkerState,
        event: PortalWorkerEvent,
    ): PortalWorkerTransition =
        when (state) {
        PortalWorkerState.AwaitingRequest -> when (event) {
            is PortalWorkerEvent.FrameReceived -> if (event.frame.kind == PortalKind.Request) {
                PortalWorkerTransition.Dispatch(PortalWorkerState.Dispatching(event.frame), event.frame)
            } else {
                PortalWorkerTransition.Drop(state)
            }
            PortalWorkerEvent.IdleTick -> PortalWorkerTransition.Await(state)
            PortalWorkerEvent.PeerClosed -> PortalWorkerTransition.Close(PortalWorkerState.Stopped)
            is PortalWorkerEvent.DispatchSucceeded,
            is PortalWorkerEvent.DispatchFailed,
            -> PortalWorkerTransition.Drop(state)
        }
        is PortalWorkerState.Dispatching -> when (event) {
            is PortalWorkerEvent.DispatchSucceeded -> reply(state.request, PortalKind.Response, event.payload)
            is PortalWorkerEvent.DispatchFailed -> reply(state.request, PortalKind.Error, event.message)
            PortalWorkerEvent.PeerClosed -> PortalWorkerTransition.Close(PortalWorkerState.Stopped)
            is PortalWorkerEvent.FrameReceived,
            PortalWorkerEvent.IdleTick,
            -> PortalWorkerTransition.Drop(state)
        }
        PortalWorkerState.Stopped -> PortalWorkerTransition.Close(PortalWorkerState.Stopped)
    }

    private fun reply(
        request: PortalFrame,
        kind: PortalKind,
        payload: PortalPayload,
    ): PortalWorkerTransition {
        val frame = PortalFrame(kind, request.requestId, request.method, payload, 0)
        return PortalWorkerTransition.Send(PortalWorkerState.AwaitingRequest, frame)
    }
}
