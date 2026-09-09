package io.mazewall.enforcer.supervisor

import io.mazewall.NativeEngine
import io.mazewall.ffi.memory.NativeArena
import java.util.concurrent.CompletableFuture

/** Routes create terminal effects; async completion carries no confined memory across threads. */
internal class SupervisorRouteExecutor(
    engine: NativeEngine,
    descriptors: TraceeDescriptorImporter,
    helpers: SocketHelperPool = SupervisorSocketHelpers.pool,
) : AutoCloseable {
    private val open = OpenRouteExecutor(engine, descriptors)
    private val sockets = PoolSocketRouteExecutor(descriptors, helpers.session())
    private val exec = SecureExecRouteExecutor(engine)

    context(arena: NativeArena) fun inject(context: SupervisorRouteContext): CompletableFuture<SupervisorReplyEffect> {
        val header = context.request.header
        return when (val plan = SupervisorInjectionPlan.create(header.nr, header.arch, header.args, context.extracted)) {
            is SupervisorInjectionPlan.Open -> CompletableFuture.completedFuture(open.execute(plan.request, header.tid))
            is SupervisorInjectionPlan.Connect -> sockets.connect(header.tid, header.args[0].toInt(), plan.sockaddr)
            is SupervisorInjectionPlan.Deny -> CompletableFuture.completedFuture(SupervisorReplyEffect.Error(plan.errno))
            SupervisorInjectionPlan.Accept -> sockets.accept(
                AcceptRequest(header.tid, header.args[0].toInt(), header.args[1], header.args[2], if (header.nr == header.arch.accept4) header.args[ACCEPT_FLAGS_ARGUMENT].toInt() else 0),
            )
        }
    }

    context(arena: NativeArena) fun secureExec(
        context: SupervisorRouteContext,
        path: String?,
    ): SupervisorReplyEffect = exec.execute(context, path)

    override fun close() = sockets.close()

    private companion object {
        const val ACCEPT_FLAGS_ARGUMENT = 3
    }
}
