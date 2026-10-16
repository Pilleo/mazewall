package io.mazewall.portal

internal enum class StatefulGroupPhase { AVAILABLE, ACTIVE, STATE_LOST }

internal enum class StatefulGroupEvent { ADMIT, COMPLETED, LOST }

/** Atomic admission and terminal loss share one pure decision authority. */
internal object PortalStatefulAdmission {
    fun evaluate(
        phase: StatefulGroupPhase,
        event: StatefulGroupEvent,
    ): StatefulGroupPhase =
        when (event) {
        StatefulGroupEvent.ADMIT -> when (phase) {
            StatefulGroupPhase.AVAILABLE -> StatefulGroupPhase.ACTIVE
            StatefulGroupPhase.ACTIVE, StatefulGroupPhase.STATE_LOST -> phase
        }
        StatefulGroupEvent.COMPLETED -> when (phase) {
            StatefulGroupPhase.ACTIVE -> StatefulGroupPhase.AVAILABLE
            StatefulGroupPhase.AVAILABLE, StatefulGroupPhase.STATE_LOST -> phase
        }
        StatefulGroupEvent.LOST -> StatefulGroupPhase.STATE_LOST
    }
}
