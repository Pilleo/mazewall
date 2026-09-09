package io.mazewall.enforcer.supervisor

import io.mazewall.ffi.memory.NativeArena
import java.util.concurrent.CompletableFuture

/** Each worker confines its own memory and publishes only heap values and owned descriptors. */
internal class AsyncSocketRouteExecutor(
    private val sockets: SocketRouteExecutor,
) {
    fun execute(
        id: Long,
        request: AcceptRequest,
    ): CompletableFuture<SupervisorReplyEffect> {
        val result = CompletableFuture<SupervisorReplyEffect>()
        Thread.ofPlatform().daemon().name("supervisor-accept-worker-$id").start {
            try {
                val effect = NativeArena.ofConfined().use { arena -> with(arena) { sockets.accept(request) } }
                result.complete(effect)
            } catch (expectedAsyncFailure: Exception) {
                java.util.logging.Logger
                    .getLogger(AsyncSocketRouteExecutor::class.java.name)
                    .log(java.util.logging.Level.WARNING, "Async accept failed for notification $id", expectedAsyncFailure)
                result.complete(SupervisorReplyEffect.Error())
            }
        }
        return result
    }
}
