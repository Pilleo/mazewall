package io.mazewall.koog

import io.mazewall.enforcer.api.execution.InvocationHandle
import io.mazewall.platform.fd.CleanupFailures
import io.mazewall.platform.lifecycle.MazewallInternal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

@OptIn(MazewallInternal::class)
internal suspend fun <R> awaitThreadInvocation(submit: () -> InvocationHandle<R>): R = ThreadInvocationAwait(submit).await()

// A retained receiver avoids the compiler's null submit-parameter placeholder on coroutine reentry.
@OptIn(MazewallInternal::class)
private class ThreadInvocationAwait<R>(
    private val submit: () -> InvocationHandle<R>,
) {
    @Suppress("TooGenericExceptionCaught", "InjectDispatcher") // Host IO is required; retain all cancellation cleanup failures, including fatal errors.
    suspend fun await(): R {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val handle = submit()
        try {
            return runInterruptible(Dispatchers.IO) { handle.result().toCompletableFuture().get() }
        } catch (failure: Throwable) {
            // Unwrap outside runInterruptible: a body InterruptedException is not wait cancellation.
            val cause = unwrapFutureFailure(failure)
            if (!caller.isActive) {
                try {
                    handle.requestCancellation()
                } catch (requestFailure: Throwable) {
                    throw CleanupFailures.merge(cause, requestFailure)
                }
            }
            throw cause
        }
    }
}

private tailrec fun unwrapFutureFailure(failure: Throwable): Throwable {
    val cause = failure.cause
    return if ((failure is ExecutionException || failure is CompletionException) && cause != null) {
        unwrapFutureFailure(cause)
    } else {
        failure
    }
}
