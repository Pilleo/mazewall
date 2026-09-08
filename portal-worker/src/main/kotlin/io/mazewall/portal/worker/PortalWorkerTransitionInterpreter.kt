package io.mazewall.portal.worker

import io.mazewall.portal.PortalFrame

/** Pure mapping from reducer transitions to the I/O loop's permitted actions. */
internal object PortalWorkerTransitionInterpreter {
    fun interpret(transition: PortalWorkerTransition): PortalWorkerLoopAction =
        when (transition) {
            is PortalWorkerTransition.Dispatch -> PortalWorkerLoopAction.Dispatch(transition.request, transition.state)
            is PortalWorkerTransition.Await,
            is PortalWorkerTransition.Send,
            is PortalWorkerTransition.Close,
            -> PortalWorkerLoopAction.Continue
            is PortalWorkerTransition.ProtocolViolation -> PortalWorkerLoopAction.Terminate(transition.reason)
        }
}

internal sealed interface PortalWorkerLoopAction {
    data class Dispatch(
        val request: PortalFrame,
        val state: PortalWorkerState.Dispatching,
    ) : PortalWorkerLoopAction

    data object Continue : PortalWorkerLoopAction

    data class Terminate(
        val reason: String,
    ) : PortalWorkerLoopAction
}
