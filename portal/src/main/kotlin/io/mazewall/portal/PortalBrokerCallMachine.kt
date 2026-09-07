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
        val payload: ByteArray,
    ) : PortalBrokerCallEffect

    data class RemoteError(
        val message: String,
    ) : PortalBrokerCallEffect

    data class RecycleWorker(
        val reason: String,
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
            is PortalBrokerCallEvent.ReplyReceived,
            PortalBrokerCallEvent.TransportFailed,
            -> failed("portal RPC failed")
        }
        is PortalBrokerCallState.AwaitingReply -> when (event) {
            is PortalBrokerCallEvent.RequestSent -> failed("portal RPC failed")
            PortalBrokerCallEvent.TransportFailed -> failed("portal RPC failed")
            is PortalBrokerCallEvent.ReplyReceived -> evaluateReply(state.request, event.reply)
        }
        PortalBrokerCallState.Completed,
        PortalBrokerCallState.Failed,
        -> PortalBrokerCallTransition(state, PortalBrokerCallEffect.None)
    }

    private fun evaluateReply(
        request: PortalFrame,
        reply: PortalFrame,
    ): PortalBrokerCallTransition {
        if (reply.requestId != request.requestId) return failed("request id mismatch")
        return when (reply.kind) {
            PortalKind.Response -> PortalBrokerCallTransition(PortalBrokerCallState.Completed, PortalBrokerCallEffect.ReturnPayload(reply.payload))
            PortalKind.Error -> PortalBrokerCallTransition(PortalBrokerCallState.Completed, PortalBrokerCallEffect.RemoteError(reply.payload.toString(StandardCharsets.UTF_8)))
            PortalKind.Request -> failed("unexpected request frame")
        }
    }

    private fun failed(reason: String) = PortalBrokerCallTransition(PortalBrokerCallState.Failed, PortalBrokerCallEffect.RecycleWorker(reason))
}
