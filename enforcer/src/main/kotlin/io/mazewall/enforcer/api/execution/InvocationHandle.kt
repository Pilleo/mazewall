package io.mazewall.enforcer.api.execution

import java.util.concurrent.CompletionStage

/** A request for cooperative cancellation is not proof of physical execution end. */
public enum class CancellationDisposition { REQUESTED, ALREADY_ENDED }

/** Supported body and cleanup ended, or the body is known never to have started. */
public sealed interface ExecutionEnd {
    public data class NotStarted(
        public val failure: Throwable,
    ) : ExecutionEnd

    public data class Finished(
        public val failure: Throwable?,
    ) : ExecutionEnd
}

/** Delivery and physical lifetime are separate; converted futures do not own execution. */
public interface InvocationHandle<T> {
    public fun result(): CompletionStage<T>

    public fun executionEnd(): CompletionStage<ExecutionEnd>

    public fun requestCancellation(): CancellationDisposition
}
