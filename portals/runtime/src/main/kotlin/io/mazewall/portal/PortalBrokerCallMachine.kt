package io.mazewall.portal

/** Pure lifecycle for one broker-to-worker RPC; socket and FD operations stay in its interpreter. */
internal sealed interface PortalBrokerCallState {
    data object Idle : PortalBrokerCallState

    data class AwaitingReply(
        val request: PortalFrame,
    ) : PortalBrokerCallState

    data class ValidatingReply(
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

    data object ReplyValidated : PortalBrokerCallEvent

    data class ReplyValidationFailed(
        val reason: String,
    ) : PortalBrokerCallEvent

    data object TransportFailed : PortalBrokerCallEvent
}

internal sealed interface PortalBrokerCallEffect {
    data class ValidatePayload(
        val payload: PortalPayload,
    ) : PortalBrokerCallEffect

    data object ReleaseWorker : PortalBrokerCallEffect

    data class RemoteError(
        val payload: PortalPayload,
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

    /** Transmission completed; awaiting a reply is a named progress outcome. */
    data object AwaitReply : PortalBrokerCallEffect
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
            PortalBrokerCallState.Idle -> fromIdle(event)
            is PortalBrokerCallState.AwaitingReply -> fromAwaitingReply(state, event)
            is PortalBrokerCallState.ValidatingReply -> fromValidatingReply(event)
            PortalBrokerCallState.Completed,
            PortalBrokerCallState.Failed,
            -> PortalBrokerCallTransition(state, PortalBrokerCallEffect.PostTerminalObservation(state, event))
        }

    private fun fromIdle(event: PortalBrokerCallEvent): PortalBrokerCallTransition =
        when (event) {
            is PortalBrokerCallEvent.RequestSent -> PortalBrokerCallTransition(PortalBrokerCallState.AwaitingReply(event.request), PortalBrokerCallEffect.AwaitReply)
            is PortalBrokerCallEvent.ReplyReceived -> protocolViolation("reply received before request")
            PortalBrokerCallEvent.ReplyValidated -> protocolViolation("reply validated before request")
            is PortalBrokerCallEvent.ReplyValidationFailed -> protocolViolation(event.reason)
            PortalBrokerCallEvent.TransportFailed -> failed("portal RPC failed")
        }

    private fun fromAwaitingReply(
        state: PortalBrokerCallState.AwaitingReply,
        event: PortalBrokerCallEvent,
    ): PortalBrokerCallTransition =
        when (event) {
            is PortalBrokerCallEvent.RequestSent -> protocolViolation("request sent while awaiting reply")
            PortalBrokerCallEvent.ReplyValidated -> protocolViolation("reply validated before reply received")
            is PortalBrokerCallEvent.ReplyValidationFailed -> protocolViolation(event.reason)
            PortalBrokerCallEvent.TransportFailed -> failed("portal RPC failed")
            is PortalBrokerCallEvent.ReplyReceived -> evaluateReply(state.request, event.reply)
        }

    private fun fromValidatingReply(event: PortalBrokerCallEvent): PortalBrokerCallTransition =
        when (event) {
            PortalBrokerCallEvent.ReplyValidated -> PortalBrokerCallTransition(PortalBrokerCallState.Completed, PortalBrokerCallEffect.ReleaseWorker)
            is PortalBrokerCallEvent.ReplyValidationFailed -> protocolViolation(event.reason)
            PortalBrokerCallEvent.TransportFailed -> failed("portal RPC failed")
            is PortalBrokerCallEvent.RequestSent -> protocolViolation("request sent while validating reply")
            is PortalBrokerCallEvent.ReplyReceived -> protocolViolation("duplicate reply while validating reply")
        }

    private fun evaluateReply(
        request: PortalFrame,
        reply: PortalFrame,
    ): PortalBrokerCallTransition {
        if (reply.requestId != request.requestId) return protocolViolation("request id mismatch")
        if (reply.method != request.method) return protocolViolation("method mismatch")
        return when (reply.kind) {
            PortalKind.Response -> PortalBrokerCallTransition(PortalBrokerCallState.ValidatingReply(request), PortalBrokerCallEffect.ValidatePayload(reply.payload))
            PortalKind.Error -> PortalBrokerCallTransition(PortalBrokerCallState.Completed, PortalBrokerCallEffect.RemoteError(reply.payload))
            PortalKind.Request -> protocolViolation("unexpected request frame")
        }
    }

    private fun failed(reason: String) = PortalBrokerCallTransition(PortalBrokerCallState.Failed, PortalBrokerCallEffect.RecycleWorker(reason))

    private fun protocolViolation(reason: String) = PortalBrokerCallTransition(PortalBrokerCallState.Failed, PortalBrokerCallEffect.ProtocolViolation(reason))
}
