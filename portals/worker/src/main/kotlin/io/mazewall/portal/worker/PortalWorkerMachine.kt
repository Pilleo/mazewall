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

    data class SendingReply(
        val request: PortalFrame,
        val reply: PortalFrame,
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

    data object ReplySucceeded : PortalWorkerEvent

    data class ReplyFailed(
        val reason: String,
    ) : PortalWorkerEvent

    data object FatalDispatchFailed : PortalWorkerEvent

    /** Any fatal driver or transport failure ends this worker before it is propagated. */
    data object FatalFailure : PortalWorkerEvent
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

    /** A peer or local driver violated the one-request-at-a-time worker protocol. */
    data class ProtocolViolation(
        override val state: PortalWorkerState.Stopped,
        val reason: String,
    ) : PortalWorkerTransition

    data class Await(
        override val state: PortalWorkerState,
    ) : PortalWorkerTransition

    data class Close(
        override val state: PortalWorkerState.Stopped,
    ) : PortalWorkerTransition

    /** Late events cannot dispatch, transmit, or schedule another close after termination. */
    data class PostTerminalObservation(
        val event: PortalWorkerEvent,
    ) : PortalWorkerTransition {
        override val state: PortalWorkerState.Stopped = PortalWorkerState.Stopped
    }
}

internal object PortalWorkerMachine {
    fun evaluate(
        state: PortalWorkerState,
        event: PortalWorkerEvent,
    ): PortalWorkerTransition =
        when (state) {
        PortalWorkerState.AwaitingRequest -> awaiting(event)
        is PortalWorkerState.Dispatching -> dispatching(state, event)
        is PortalWorkerState.SendingReply -> sending(event)
        PortalWorkerState.Stopped -> PortalWorkerTransition.PostTerminalObservation(event)
    }

    private fun awaiting(event: PortalWorkerEvent): PortalWorkerTransition =
        when (event) {
            is PortalWorkerEvent.FrameReceived -> if (event.frame.kind == PortalKind.Request) {
                PortalWorkerTransition.Dispatch(PortalWorkerState.Dispatching(event.frame), event.frame)
            } else {
                violation("expected a request frame")
            }
            PortalWorkerEvent.IdleTick -> PortalWorkerTransition.Await(PortalWorkerState.AwaitingRequest)
            PortalWorkerEvent.PeerClosed -> PortalWorkerTransition.Close(PortalWorkerState.Stopped)
            PortalWorkerEvent.FatalFailure -> PortalWorkerTransition.Close(PortalWorkerState.Stopped)
            is PortalWorkerEvent.DispatchSucceeded,
            is PortalWorkerEvent.DispatchFailed,
            PortalWorkerEvent.ReplySucceeded,
            is PortalWorkerEvent.ReplyFailed,
            PortalWorkerEvent.FatalDispatchFailed,
            -> violation("received a dispatch result without a request")
        }

    private fun dispatching(
        state: PortalWorkerState.Dispatching,
        event: PortalWorkerEvent,
    ): PortalWorkerTransition =
        when (event) {
            is PortalWorkerEvent.DispatchSucceeded -> reply(state.request, PortalKind.Response, event.payload)
            is PortalWorkerEvent.DispatchFailed -> reply(state.request, PortalKind.Error, event.message)
            PortalWorkerEvent.FatalDispatchFailed -> PortalWorkerTransition.Close(PortalWorkerState.Stopped)
            PortalWorkerEvent.FatalFailure -> PortalWorkerTransition.Close(PortalWorkerState.Stopped)
            PortalWorkerEvent.PeerClosed -> PortalWorkerTransition.Close(PortalWorkerState.Stopped)
            is PortalWorkerEvent.FrameReceived -> violation("received a second frame while dispatching")
            PortalWorkerEvent.IdleTick -> PortalWorkerTransition.Await(state)
            PortalWorkerEvent.ReplySucceeded,
            is PortalWorkerEvent.ReplyFailed,
            -> violation("received a reply result before sending a reply")
        }

    private fun sending(event: PortalWorkerEvent): PortalWorkerTransition =
        when (event) {
            PortalWorkerEvent.ReplySucceeded -> PortalWorkerTransition.Await(PortalWorkerState.AwaitingRequest)
            is PortalWorkerEvent.ReplyFailed -> PortalWorkerTransition.Close(PortalWorkerState.Stopped)
            PortalWorkerEvent.PeerClosed -> PortalWorkerTransition.Close(PortalWorkerState.Stopped)
            is PortalWorkerEvent.FrameReceived,
            is PortalWorkerEvent.DispatchSucceeded,
            is PortalWorkerEvent.DispatchFailed,
            PortalWorkerEvent.IdleTick,
            PortalWorkerEvent.FatalDispatchFailed,
            -> violation("received an event while sending a reply")
            PortalWorkerEvent.FatalFailure -> PortalWorkerTransition.Close(PortalWorkerState.Stopped)
        }

    private fun reply(
        request: PortalFrame,
        kind: PortalKind,
        payload: PortalPayload,
    ): PortalWorkerTransition.Send {
        val frame = PortalFrame(kind, request.requestId, request.method, payload, 0)
        return PortalWorkerTransition.Send(PortalWorkerState.SendingReply(request, frame), frame)
    }

    private fun violation(reason: String): PortalWorkerTransition.ProtocolViolation = PortalWorkerTransition.ProtocolViolation(PortalWorkerState.Stopped, reason)
}
