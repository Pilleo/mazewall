package io.mazewall.portal

internal enum class PortalInvocationPhase { QUEUED, RUNNING, RETIRING, ENDED }

internal enum class PortalInvocationEvent { START, CANCEL, BODY_ENDED, RETIREMENT_ENDED }

/** Pure transitions; cancellation never advances a running body or pending retirement to end. */
internal object PortalInvocationLifecycle {
    fun evaluate(
        phase: PortalInvocationPhase,
        event: PortalInvocationEvent,
    ): PortalInvocationPhase =
        when (phase) {
        PortalInvocationPhase.QUEUED -> when (event) {
            PortalInvocationEvent.START -> PortalInvocationPhase.RUNNING
            PortalInvocationEvent.CANCEL -> PortalInvocationPhase.ENDED
            PortalInvocationEvent.BODY_ENDED, PortalInvocationEvent.RETIREMENT_ENDED -> error("invocation never started")
        }
        PortalInvocationPhase.RUNNING -> when (event) {
            PortalInvocationEvent.CANCEL -> phase
            PortalInvocationEvent.BODY_ENDED -> PortalInvocationPhase.RETIRING
            PortalInvocationEvent.START, PortalInvocationEvent.RETIREMENT_ENDED -> error("invocation body still running")
        }
        PortalInvocationPhase.RETIRING -> when (event) {
            PortalInvocationEvent.CANCEL -> phase
            PortalInvocationEvent.RETIREMENT_ENDED -> PortalInvocationPhase.ENDED
            PortalInvocationEvent.START, PortalInvocationEvent.BODY_ENDED -> error("invocation retirement pending")
        }
        PortalInvocationPhase.ENDED -> phase
    }
}
