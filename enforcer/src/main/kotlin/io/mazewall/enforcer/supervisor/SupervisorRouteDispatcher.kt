package io.mazewall.enforcer.supervisor

import io.mazewall.ffi.NativeConstants

/** Executes classified routes while preserving the one-response notification invariant. */
internal class SupervisorRouteDispatcher(
    private val terminalRoutes: SupervisorTerminalRoutes,
) {
    fun execute(
        route: SupervisorRoute,
        context: SupervisorRouteContext,
        jvmPath: String?,
        injectFd: (SupervisorRouteContext) -> Boolean,
        secureExec: (SupervisorRouteContext, String?) -> Boolean,
    ): Boolean = terminalRoutes.execute(route, context) ?: when (route) {
        is SupervisorRoute.InjectFd -> injectFd(context)
        is SupervisorRoute.SecureExec -> secureExec(context, jvmPath)
        is SupervisorRoute.AskJvm -> terminalRoutes.abort(context, NativeConstants.EPERM)
        is SupervisorRoute.Continue,
        is SupervisorRoute.Abort,
        -> error("terminal route must have been handled")
    }
}
