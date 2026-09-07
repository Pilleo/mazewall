package io.mazewall.enforcer.supervisor

import io.mazewall.ffi.NativeConstants

/** Pure notification-session lifecycle; I/O and seccomp replies are interpreted by the handler. */
internal sealed interface SupervisorSessionState {
    data object AwaitingNotification : SupervisorSessionState

    data object ResolvingPath : SupervisorSessionState

    data object AwaitingJvmVerdict : SupervisorSessionState

    data object Replied : SupervisorSessionState
}

internal sealed interface SupervisorSessionEvent {
    data object NotificationReceived : SupervisorSessionEvent

    data class PathResolved(
        val route: SupervisorRoute,
    ) : SupervisorSessionEvent

    data class JvmVerdictReceived(
        val route: SupervisorRoute,
    ) : SupervisorSessionEvent

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
            SupervisorSessionState.AwaitingNotification ->
                if (event is SupervisorSessionEvent.NotificationReceived) {
                    SupervisorSessionTransition(SupervisorSessionState.ResolvingPath, SupervisorSessionEffect.ResolvePath)
                } else {
                    SupervisorSessionTransition(state)
                }
            SupervisorSessionState.ResolvingPath ->
                when (event) {
                    is SupervisorSessionEvent.PathResolved -> routeTransition(event.route)
                    SupervisorSessionEvent.Failed -> abort()
                    SupervisorSessionEvent.NotificationReceived,
                    is SupervisorSessionEvent.JvmVerdictReceived,
                    -> SupervisorSessionTransition(state)
                }
            SupervisorSessionState.AwaitingJvmVerdict ->
                when (event) {
                    is SupervisorSessionEvent.JvmVerdictReceived -> terminalRouteTransition(event.route)
                    SupervisorSessionEvent.Failed -> abort()
                    SupervisorSessionEvent.NotificationReceived,
                    is SupervisorSessionEvent.PathResolved,
                    -> SupervisorSessionTransition(state)
                }
            SupervisorSessionState.Replied -> SupervisorSessionTransition(state)
        }

    private fun routeTransition(route: SupervisorRoute): SupervisorSessionTransition =
        if (route is SupervisorRoute.AskJvm) {
            SupervisorSessionTransition(SupervisorSessionState.AwaitingJvmVerdict, SupervisorSessionEffect.RequestJvmVerdict)
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
            -> SupervisorSessionTransition(SupervisorSessionState.Replied, SupervisorSessionEffect.ExecuteRoute(route))
        }

    private fun abort(): SupervisorSessionTransition = SupervisorSessionTransition(SupervisorSessionState.Replied, SupervisorSessionEffect.Abort())
}
