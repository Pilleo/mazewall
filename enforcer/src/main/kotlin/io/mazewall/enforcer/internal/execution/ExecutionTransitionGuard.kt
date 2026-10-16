package io.mazewall.enforcer.internal.execution

internal sealed interface ExecutionTransition {
    data object Dispatch : ExecutionTransition

    data object InlineCurrentWorker : ExecutionTransition

    data object Reject : ExecutionTransition
}

/** Identity and active operation decide dispatch; metadata never widens native permissions. */
internal object ExecutionTransitionGuard {
    fun evaluate(
        ownedWorker: Boolean,
        sameDomain: Boolean,
        activeInvocation: Boolean,
        unknownThreadScope: Boolean,
    ): ExecutionTransition =
        if (!ownedWorker) {
            if (unknownThreadScope) ExecutionTransition.Reject else ExecutionTransition.Dispatch
        } else {
            if (sameDomain && activeInvocation) ExecutionTransition.InlineCurrentWorker else ExecutionTransition.Reject
        }
}
