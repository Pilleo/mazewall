package io.mazewall.enforcer.supervisor

import io.mazewall.ffi.NativeConstants

/** Pure notification-session lifecycle; I/O and seccomp replies are interpreted by the handler. */
internal sealed interface SupervisorSessionState {
    data object AwaitingNotification : SupervisorSessionState

    data object ResolvingPath : SupervisorSessionState

    data object SendingJvmRequest : SupervisorSessionState

    data object AwaitingJvmVerdict : SupervisorSessionState

    data object ExecutingReply : SupervisorSessionState

    data object Replied : SupervisorSessionState

    /** Terminal without a confirmed kernel reply; the owning session must be recycled. */
    data object Terminated : SupervisorSessionState
}

internal sealed interface SupervisorSessionEvent {
    data object NotificationReceived : SupervisorSessionEvent

    data class PathResolved(
        val route: SupervisorRoute,
    ) : SupervisorSessionEvent

    data class JvmVerdictReceived(
        val route: SupervisorRoute,
    ) : SupervisorSessionEvent

    data object JvmRequestSent : SupervisorSessionEvent

    data object JvmRequestFailed : SupervisorSessionEvent

    data object ReplySucceeded : SupervisorSessionEvent

    data object ReplyFailed : SupervisorSessionEvent

    data object Failed : SupervisorSessionEvent
}

internal sealed interface SupervisorSessionEffect {
    data object ResolvePath : SupervisorSessionEffect

    data object RequestJvmVerdict : SupervisorSessionEffect

    data class ExecuteRoute(
        val route: SupervisorRoute,
    ) : SupervisorSessionEffect

    data class Abort(
        val errno: Int = NativeConstants.EPERM,
    ) : SupervisorSessionEffect

    /** An impossible lifecycle edge; terminate/recycle without attempting another kernel reply. */
    data class ProtocolViolation(
        val from: SupervisorSessionState,
        val event: SupervisorSessionEvent,
    ) : SupervisorSessionEffect

    /** An explicitly harmless observation after this single-notification session is terminal. */
    data object PostTerminalObservation : SupervisorSessionEffect
}

internal data class SupervisorSessionTransition(
    val state: SupervisorSessionState,
    val effect: SupervisorSessionEffect? = null,
)

internal object SupervisorSessionMachine {
    fun evaluate(
        state: SupervisorSessionState,
        event: SupervisorSessionEvent,
    ): SupervisorSessionTransition =
        when (state) {
            SupervisorSessionState.AwaitingNotification -> when (event) {
                SupervisorSessionEvent.NotificationReceived ->
                    SupervisorSessionTransition(SupervisorSessionState.ResolvingPath, SupervisorSessionEffect.ResolvePath)
                is SupervisorSessionEvent.PathResolved,
                is SupervisorSessionEvent.JvmVerdictReceived,
                SupervisorSessionEvent.JvmRequestSent,
                SupervisorSessionEvent.JvmRequestFailed,
                SupervisorSessionEvent.ReplySucceeded,
                SupervisorSessionEvent.ReplyFailed,
                SupervisorSessionEvent.Failed,
                -> protocolViolation(state, event)
            }
            SupervisorSessionState.ResolvingPath ->
                when (event) {
                    is SupervisorSessionEvent.PathResolved -> routeTransition(event.route)
                    SupervisorSessionEvent.Failed -> abort()
                    SupervisorSessionEvent.NotificationReceived,
                    is SupervisorSessionEvent.JvmVerdictReceived,
                    SupervisorSessionEvent.JvmRequestSent,
                    SupervisorSessionEvent.JvmRequestFailed,
                    SupervisorSessionEvent.ReplySucceeded,
                    SupervisorSessionEvent.ReplyFailed,
                    -> protocolViolation(state, event)
                }
            SupervisorSessionState.SendingJvmRequest ->
                when (event) {
                    SupervisorSessionEvent.JvmRequestSent -> SupervisorSessionTransition(SupervisorSessionState.AwaitingJvmVerdict)
                    SupervisorSessionEvent.JvmRequestFailed,
                    SupervisorSessionEvent.Failed,
                    -> abort()
                    SupervisorSessionEvent.NotificationReceived,
                    is SupervisorSessionEvent.PathResolved,
                    is SupervisorSessionEvent.JvmVerdictReceived,
                    SupervisorSessionEvent.ReplySucceeded,
                    SupervisorSessionEvent.ReplyFailed,
                    -> protocolViolation(state, event)
                }
            SupervisorSessionState.AwaitingJvmVerdict ->
                when (event) {
                    is SupervisorSessionEvent.JvmVerdictReceived -> terminalRouteTransition(event.route)
                    SupervisorSessionEvent.Failed -> abort()
                    SupervisorSessionEvent.NotificationReceived,
                    is SupervisorSessionEvent.PathResolved,
                    SupervisorSessionEvent.JvmRequestSent,
                    SupervisorSessionEvent.JvmRequestFailed,
                    SupervisorSessionEvent.ReplySucceeded,
                    SupervisorSessionEvent.ReplyFailed,
                    -> protocolViolation(state, event)
                }
            SupervisorSessionState.ExecutingReply ->
                when (event) {
                    SupervisorSessionEvent.ReplySucceeded -> SupervisorSessionTransition(SupervisorSessionState.Replied)
                    SupervisorSessionEvent.ReplyFailed,
                    SupervisorSessionEvent.Failed,
                    -> SupervisorSessionTransition(SupervisorSessionState.Terminated)
                    SupervisorSessionEvent.NotificationReceived,
                    is SupervisorSessionEvent.PathResolved,
                    is SupervisorSessionEvent.JvmVerdictReceived,
                    SupervisorSessionEvent.JvmRequestSent,
                    SupervisorSessionEvent.JvmRequestFailed,
                    -> protocolViolation(state, event)
                }
            SupervisorSessionState.Replied,
            SupervisorSessionState.Terminated,
            -> SupervisorSessionTransition(state, SupervisorSessionEffect.PostTerminalObservation)
        }

    private fun routeTransition(route: SupervisorRoute): SupervisorSessionTransition =
        if (route is SupervisorRoute.AskJvm) {
            SupervisorSessionTransition(SupervisorSessionState.SendingJvmRequest, SupervisorSessionEffect.RequestJvmVerdict)
        } else {
            terminalRouteTransition(route)
        }

    private fun terminalRouteTransition(route: SupervisorRoute): SupervisorSessionTransition =
        when (route) {
            is SupervisorRoute.AskJvm -> abort()
            is SupervisorRoute.Continue,
            is SupervisorRoute.Abort,
            is SupervisorRoute.InjectFd,
            is SupervisorRoute.SecureExec,
            -> SupervisorSessionTransition(SupervisorSessionState.ExecutingReply, SupervisorSessionEffect.ExecuteRoute(route))
        }

    private fun abort(): SupervisorSessionTransition = SupervisorSessionTransition(SupervisorSessionState.ExecutingReply, SupervisorSessionEffect.Abort())

    private fun protocolViolation(
        state: SupervisorSessionState,
        event: SupervisorSessionEvent,
    ): SupervisorSessionTransition = SupervisorSessionTransition(SupervisorSessionState.Terminated, SupervisorSessionEffect.ProtocolViolation(state, event))
}
