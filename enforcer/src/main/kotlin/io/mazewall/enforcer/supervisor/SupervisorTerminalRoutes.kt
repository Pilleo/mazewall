package io.mazewall.enforcer.supervisor

import io.mazewall.ffi.memory.ManagedSegment

/** Emits the one terminal response required for routes that do not need supervisor I/O. */
internal interface SupervisorResponseSender {
    fun continueNotification(
        id: Long,
        response: ManagedSegment,
    )

    fun abortNotification(
        id: Long,
        errno: Int,
        response: ManagedSegment,
    )
}

/** Executes terminal route decisions without exposing seccomp response mechanics to the router. */
internal class SupervisorTerminalRoutes(
    private val sender: SupervisorResponseSender,
) {
    private val replies = SupervisorReplyInterpreter(sender)

    fun execute(
        route: SupervisorRoute,
        context: SupervisorRouteContext,
    ): Boolean? =
        when (route) {
        is SupervisorRoute.Continue -> replies.execute(context.request.id, context.response, SupervisorReplyInterpreter.Decision.Continue)
        is SupervisorRoute.Abort -> replies.execute(context.request.id, context.response, SupervisorReplyInterpreter.Decision.Abort(route.errno))
        is SupervisorRoute.AskJvm,
        is SupervisorRoute.InjectFd,
        is SupervisorRoute.SecureExec,
        -> null
    }

    fun abort(
        context: SupervisorRouteContext,
        errno: Int,
    ): Boolean {
        replies.execute(context.request.id, context.response, SupervisorReplyInterpreter.Decision.Abort(errno))
        return false
    }
}
