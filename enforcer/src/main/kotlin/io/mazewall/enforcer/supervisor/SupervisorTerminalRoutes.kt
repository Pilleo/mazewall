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
    fun execute(
        route: SupervisorRoute,
        context: SupervisorRouteContext,
    ): Boolean? =
        when (route) {
        is SupervisorRoute.Continue -> {
            sender.continueNotification(context.request.id, context.response)
            true
        }
        is SupervisorRoute.Abort -> {
            sender.abortNotification(context.request.id, route.errno, context.response)
            true
        }
        is SupervisorRoute.AskJvm,
        is SupervisorRoute.InjectFd,
        is SupervisorRoute.SecureExec,
        -> null
    }

    fun abort(
        context: SupervisorRouteContext,
        errno: Int,
    ): Boolean {
        sender.abortNotification(context.request.id, errno, context.response)
        return false
    }
}
