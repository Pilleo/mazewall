package io.mazewall.portal

import java.nio.charset.StandardCharsets

/** Pure lifecycle for one broker-to-worker RPC; socket and FD operations stay in its interpreter. */
internal sealed interface PortalBrokerCallState {
    data object Idle : PortalBrokerCallState

    data class AwaitingReply(
        val request: PortalFrame,
    ) : PortalBrokerCallState

    data object Completed : PortalBrokerCallState

    data object Failed : PortalBrokerCallState
}

internal sealed interface PortalBrokerCallEvent {
    data class RequestSent(
        val request: PortalFrame,
    ) : PortalBrokerCallEvent

    data class ReplyReceived(
        val reply: PortalFrame,
    ) : PortalBrokerCallEvent

    data object TransportFailed : PortalBrokerCallEvent
}

internal sealed interface PortalBrokerCallEffect {
    data class ReturnPayload(
        val payload: PortalPayload,
    ) : PortalBrokerCallEffect

    data class RemoteError(
        val message: String,
    ) : PortalBrokerCallEffect

    data class RecycleWorker(
        val reason: String,
    ) : PortalBrokerCallEffect

    /** The worker crossed the broker protocol boundary out of order; recycle it fail-closed. */
    data class ProtocolViolation(
        val reason: String,
    ) : PortalBrokerCallEffect

    /** A late event after the call was already resolved; it is never an ordinary empty result. */
    data class PostTerminalObservation(
        val state: PortalBrokerCallState,
        val event: PortalBrokerCallEvent,
    ) : PortalBrokerCallEffect

    data object None : PortalBrokerCallEffect
}

internal data class PortalBrokerCallTransition(
    val state: PortalBrokerCallState,
    val effect: PortalBrokerCallEffect,
)

internal object PortalBrokerCallMachine {
    fun evaluate(
        state: PortalBrokerCallState,
        event: PortalBrokerCallEvent,
    ): PortalBrokerCallTransition =
        when (state) {
        PortalBrokerCallState.Idle -> when (event) {
            is PortalBrokerCallEvent.RequestSent -> PortalBrokerCallTransition(PortalBrokerCallState.AwaitingReply(event.request), PortalBrokerCallEffect.None)
            is PortalBrokerCallEvent.ReplyReceived -> protocolViolation("reply received before request")
            PortalBrokerCallEvent.TransportFailed -> failed("portal RPC failed")
        }
        is PortalBrokerCallState.AwaitingReply -> when (event) {
            is PortalBrokerCallEvent.RequestSent -> protocolViolation("request sent while awaiting reply")
            PortalBrokerCallEvent.TransportFailed -> failed("portal RPC failed")
            is PortalBrokerCallEvent.ReplyReceived -> evaluateReply(state.request, event.reply)
        }
        PortalBrokerCallState.Completed,
        PortalBrokerCallState.Failed,
        -> PortalBrokerCallTransition(state, PortalBrokerCallEffect.PostTerminalObservation(state, event))
    }

    private fun evaluateReply(
        request: PortalFrame,
        reply: PortalFrame,
    ): PortalBrokerCallTransition {
        if (reply.requestId != request.requestId) return protocolViolation("request id mismatch")
        if (reply.method != request.method) return protocolViolation("method mismatch")
        return when (reply.kind) {
            PortalKind.Response -> PortalBrokerCallTransition(PortalBrokerCallState.Completed, PortalBrokerCallEffect.ReturnPayload(reply.payload))
            PortalKind.Error -> PortalBrokerCallTransition(PortalBrokerCallState.Completed, PortalBrokerCallEffect.RemoteError(reply.payload.copyToByteArray().toString(StandardCharsets.UTF_8)))
            PortalKind.Request -> protocolViolation("unexpected request frame")
        }
    }

    private fun failed(reason: String) = PortalBrokerCallTransition(PortalBrokerCallState.Failed, PortalBrokerCallEffect.RecycleWorker(reason))

    private fun protocolViolation(reason: String) = PortalBrokerCallTransition(PortalBrokerCallState.Failed, PortalBrokerCallEffect.ProtocolViolation(reason))
}
