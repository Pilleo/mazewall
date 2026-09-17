package io.mazewall.enforcer.internal.supervisor

import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.native.NativeEngine
import java.util.concurrent.CompletableFuture

/**
 * Sole route-execution boundary for a supervisor session. Route helpers remain
 * implementation details behind this exhaustive dispatcher.
 */
internal class SupervisorRouteDispatcher(
    engine: NativeEngine,
    descriptors: TraceeDescriptorImporter,
    helpers: SocketHelperPool = SupervisorSocketHelpers.pool,
) : AutoCloseable {
    private val executor = SupervisorRouteExecutor(engine, descriptors, helpers)

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

    /** Stop route helper admission while preserving cleanup ownership inside this boundary. */
    fun cancel() = executor.cancel()

    override fun close() = executor.close()
}
