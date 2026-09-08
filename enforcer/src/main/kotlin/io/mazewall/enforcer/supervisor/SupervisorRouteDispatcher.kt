package io.mazewall.enforcer.supervisor

import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena

/** Executes classified routes while preserving the one-response notification invariant. */
internal class SupervisorRouteDispatcher(
    private val terminalRoutes: SupervisorTerminalRoutes,
) {
    context(arena: NativeArena) fun execute(
        route: SupervisorRoute,
        context: SupervisorRouteContext,
        jvmPath: String?,
        executor: SupervisorRouteExecutor,
    ): Boolean =
        terminalRoutes.execute(route, context) ?: when (route) {
        is SupervisorRoute.InjectFd -> executor.injectFd(context)
        is SupervisorRoute.SecureExec -> executor.secureExec(context, jvmPath)
        is SupervisorRoute.AskJvm -> terminalRoutes.abort(context, NativeConstants.EPERM)
        is SupervisorRoute.Continue,
        is SupervisorRoute.Abort,
        -> error("terminal route must have been handled")
    }
}
