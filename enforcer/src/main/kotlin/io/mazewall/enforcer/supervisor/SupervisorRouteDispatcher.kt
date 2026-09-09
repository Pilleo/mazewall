package io.mazewall.enforcer.supervisor

import io.mazewall.ffi.memory.NativeArena
import java.util.concurrent.CompletableFuture

/** Exhaustive dispatch preserves the JVM verdict; no route preparation can authorize another route. */
internal class SupervisorRouteDispatcher(
    private val executor: SupervisorRouteExecutor,
) {
    context(arena: NativeArena) fun execute(
        route: SupervisorRoute,
        context: SupervisorRouteContext,
        jvmPath: String?,
    ): CompletableFuture<SupervisorReplyEffect> =
        when (route) {
            SupervisorRoute.Continue -> CompletableFuture.completedFuture(SupervisorReplyEffect.Continue)
            is SupervisorRoute.Abort -> CompletableFuture.completedFuture(SupervisorReplyEffect.Error(route.errno))
            SupervisorRoute.InjectFd -> executor.inject(context)
            SupervisorRoute.SecureExec -> CompletableFuture.completedFuture(executor.secureExec(context, jvmPath))
            SupervisorRoute.AskJvm -> CompletableFuture.completedFuture(SupervisorReplyEffect.Recycle("JVM request used as a terminal route"))
        }
}
