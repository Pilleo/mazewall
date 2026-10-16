package io.mazewall.enforcer.internal.supervisor

import java.util.concurrent.CompletableFuture

/** Owns a completed native result through prerequisite cleanup and one future handoff. */
internal class SocketJobOutcome {
    val cleanup = SocketCleanup()
    var output: SocketHelperResult? = null

    fun dispose() {
        val pending = output
        output = null
        cleanup.attempt { pending?.dispose() }
    }

    fun publish(result: CompletableFuture<SocketHelperResult>) {
        val failure = cleanup.failure
        if (failure is Error) {
            result.completeExceptionally(failure)
            throw failure
        }
        if (failure != null) {
            java.util.logging.Logger
                .getLogger(SocketHelperPool::class.java.name)
                .log(java.util.logging.Level.WARNING, "Socket helper operation failed", failure)
            output = SocketHelperResult.Failed(io.mazewall.ffi.NativeConstants.EIO)
        }
        val value = output
        output = null
        if (value != null && !result.complete(value)) value.dispose()
        if (value == null) result.cancel(false)
    }
}
