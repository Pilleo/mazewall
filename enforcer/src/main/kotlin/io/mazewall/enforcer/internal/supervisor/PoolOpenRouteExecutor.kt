package io.mazewall.enforcer.internal.supervisor

import io.mazewall.platform.lifecycle.NewFdFlags
import io.mazewall.platform.lifecycle.OpenFlags
import io.mazewall.platform.lifecycle.Tid
import java.util.concurrent.CompletableFuture

/** The authorized daemon imports the directory; only capability-relative open runs in the child. */
internal class PoolOpenRouteExecutor(
    private val descriptors: TraceeDescriptorImporter,
    private val session: SocketHelperPool.Session,
) {
    fun execute(
        plan: SupervisorOpenPlan,
        tid: Tid,
    ): CompletableFuture<SupervisorReplyEffect> {
        require(!plan.useCurrentWorkingDirectory)
        val operation = SocketOperation.OpenAt2(plan.path, plan.flags, plan.mode, plan.resolve)
        val flags = NewFdFlags.forOpen(OpenFlags(plan.flags.toInt()))
        return when (val imported = descriptors.import(tid, plan.traceeDirfd.value)) {
            is SupervisorDescriptorResult.Denied -> CompletableFuture.completedFuture(SupervisorReplyEffect.Error(imported.errno))
            is SupervisorDescriptorResult.Opened -> session.submit(operation, imported.descriptor).thenApply { result ->
                when (result) {
                    is SocketHelperResult.Opened -> SupervisorReplyEffect.AddFdAndReply(result.descriptor, flags)
                    is SocketHelperResult.Failed -> SupervisorReplyEffect.Error(result.errno)
                    is SocketHelperResult.Accepted, is SocketHelperResult.Authority, SocketHelperResult.Connected -> {
                        result.dispose()
                        SupervisorReplyEffect.Recycle("Unexpected helper result for open")
                    }
                }
            }
        }
    }
}
