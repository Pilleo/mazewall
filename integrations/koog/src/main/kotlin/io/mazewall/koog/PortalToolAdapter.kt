package io.mazewall.koog

import ai.koog.agents.core.tools.Tool
import ai.koog.serialization.TypeToken
import io.mazewall.enforcer.api.execution.InvocationHandle
import io.mazewall.portal.PreparedPortalOperation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A host-selected Koog tool bound to one owned Portal operation.
 *
 * Validation, codecs, mapping and coroutine result delivery execute in the host,
 * outside worker protection. Only the generated service invocation enters the
 * protected process. Cancellation requests retirement through the original handle;
 * caller cancellation is not proof of [InvocationHandle.executionEnd]. No fallback
 * or retry is performed, including after an unknown remote result.
 */
public class PortalToolAdapter<A, R>(
    private val operation: PreparedPortalOperation<A, R>,
    argsType: TypeToken,
    resultType: TypeToken,
    name: String,
    description: String,
    private val validateArguments: (A) -> Unit,
    private val validateResult: (R) -> Unit,
) : Tool<A, R>(argsType, resultType, name, description) {
    override suspend fun execute(args: A): R {
        validateArguments(args)
        return suspendCancellableCoroutine { continuation ->
            if (!continuation.isActive) return@suspendCancellableCoroutine
            val handle = operation.submit(args)
            continuation.invokeOnCancellation { handle.requestCancellation() }
            handle.result().whenComplete { value, failure ->
                if (failure != null) {
                    continuation.resumeWithException(unwrapCompletion(failure))
                } else {
                    val checked = runCatching {
                        validateResult(value)
                        value
                    }
                    checked.fold(continuation::resume, continuation::resumeWithException)
                }
            }
        }
    }
}

private fun unwrapCompletion(failure: Throwable): Throwable = if (failure is CompletionException && failure.cause != null) unwrapCompletion(checkNotNull(failure.cause)) else failure
