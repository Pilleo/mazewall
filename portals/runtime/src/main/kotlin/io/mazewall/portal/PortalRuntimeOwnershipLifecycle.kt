package io.mazewall.portal

internal enum class PortalOwnershipPhase { OPEN, CLOSING, FAILED, CLOSED }

internal enum class PortalOwnershipEvent { CLOSE_REQUESTED, CLOSE_COMPLETED, CLOSE_FAILED }

/** Closed is published only after the interpreter proves acquisition and owner retirement. */
internal object PortalRuntimeOwnershipLifecycle {
    fun evaluate(
        phase: PortalOwnershipPhase,
        event: PortalOwnershipEvent,
    ): PortalOwnershipPhase =
        when (event) {
        PortalOwnershipEvent.CLOSE_REQUESTED -> when (phase) {
            PortalOwnershipPhase.OPEN, PortalOwnershipPhase.FAILED -> PortalOwnershipPhase.CLOSING
            PortalOwnershipPhase.CLOSING, PortalOwnershipPhase.CLOSED -> phase
        }
        PortalOwnershipEvent.CLOSE_COMPLETED -> PortalOwnershipPhase.CLOSED
        PortalOwnershipEvent.CLOSE_FAILED -> when (phase) {
            PortalOwnershipPhase.OPEN, PortalOwnershipPhase.CLOSING, PortalOwnershipPhase.FAILED -> PortalOwnershipPhase.FAILED
            PortalOwnershipPhase.CLOSED -> PortalOwnershipPhase.CLOSED
        }
    }
}
