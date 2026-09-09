package io.mazewall.portal

/** Pure authority for publishing and retiring a broker worker pool. */
internal sealed interface ProcessBrokerLifecycle {
    data object New : ProcessBrokerLifecycle

    data object Starting : ProcessBrokerLifecycle

    data object Running : ProcessBrokerLifecycle

    data object Closing : ProcessBrokerLifecycle

    data object Closed : ProcessBrokerLifecycle

    data class Failed(
        val reason: String,
    ) : ProcessBrokerLifecycle
}

internal sealed interface ProcessBrokerLifecycleEvent {
    data object StartRequested : ProcessBrokerLifecycleEvent

    data object StartupSucceeded : ProcessBrokerLifecycleEvent

    data class StartupFailed(
        val reason: String,
    ) : ProcessBrokerLifecycleEvent

    data object CloseRequested : ProcessBrokerLifecycleEvent

    data object CleanupSucceeded : ProcessBrokerLifecycleEvent

    data class CleanupFailed(
        val reason: String,
    ) : ProcessBrokerLifecycleEvent
}

internal sealed interface ProcessBrokerLifecycleTransition {
    val state: ProcessBrokerLifecycle

    data class Advanced(
        override val state: ProcessBrokerLifecycle,
    ) : ProcessBrokerLifecycleTransition

    data class ProtocolViolation(
        override val state: ProcessBrokerLifecycle,
        val reason: String,
    ) : ProcessBrokerLifecycleTransition
}

internal object ProcessBrokerLifecycleMachine {
    fun evaluate(
        state: ProcessBrokerLifecycle,
        event: ProcessBrokerLifecycleEvent,
    ): ProcessBrokerLifecycleTransition =
        when (state) {
        ProcessBrokerLifecycle.New -> fromNew(event)
        ProcessBrokerLifecycle.Starting -> fromStarting(event)
        ProcessBrokerLifecycle.Running -> fromRunning(event)
        ProcessBrokerLifecycle.Closing -> fromClosing(event)
        ProcessBrokerLifecycle.Closed -> violation(state, event)
        is ProcessBrokerLifecycle.Failed -> fromFailed(state, event)
    }

    private fun fromNew(event: ProcessBrokerLifecycleEvent) =
        when (event) {
        ProcessBrokerLifecycleEvent.StartRequested -> advanced(ProcessBrokerLifecycle.Starting)
        ProcessBrokerLifecycleEvent.CloseRequested -> advanced(ProcessBrokerLifecycle.Closing)
        ProcessBrokerLifecycleEvent.StartupSucceeded,
        is ProcessBrokerLifecycleEvent.StartupFailed,
        ProcessBrokerLifecycleEvent.CleanupSucceeded,
        is ProcessBrokerLifecycleEvent.CleanupFailed,
        -> violation(ProcessBrokerLifecycle.New, event)
        }

    private fun fromStarting(event: ProcessBrokerLifecycleEvent) =
        when (event) {
        ProcessBrokerLifecycleEvent.StartupSucceeded -> advanced(ProcessBrokerLifecycle.Running)
        is ProcessBrokerLifecycleEvent.StartupFailed -> advanced(ProcessBrokerLifecycle.Failed(event.reason))
        ProcessBrokerLifecycleEvent.CloseRequested -> advanced(ProcessBrokerLifecycle.Closing)
        ProcessBrokerLifecycleEvent.StartRequested,
        ProcessBrokerLifecycleEvent.CleanupSucceeded,
        is ProcessBrokerLifecycleEvent.CleanupFailed,
        -> violation(ProcessBrokerLifecycle.Starting, event)
        }

    private fun fromRunning(event: ProcessBrokerLifecycleEvent) =
        when (event) {
        ProcessBrokerLifecycleEvent.CloseRequested -> advanced(ProcessBrokerLifecycle.Closing)
        ProcessBrokerLifecycleEvent.StartRequested,
        ProcessBrokerLifecycleEvent.StartupSucceeded,
        is ProcessBrokerLifecycleEvent.StartupFailed,
        ProcessBrokerLifecycleEvent.CleanupSucceeded,
        is ProcessBrokerLifecycleEvent.CleanupFailed,
        -> violation(ProcessBrokerLifecycle.Running, event)
        }

    private fun fromClosing(event: ProcessBrokerLifecycleEvent) =
        when (event) {
        ProcessBrokerLifecycleEvent.CleanupSucceeded -> advanced(ProcessBrokerLifecycle.Closed)
        is ProcessBrokerLifecycleEvent.CleanupFailed -> advanced(ProcessBrokerLifecycle.Failed(event.reason))
        ProcessBrokerLifecycleEvent.StartRequested,
        ProcessBrokerLifecycleEvent.StartupSucceeded,
        is ProcessBrokerLifecycleEvent.StartupFailed,
        ProcessBrokerLifecycleEvent.CloseRequested,
        -> violation(ProcessBrokerLifecycle.Closing, event)
        }

    private fun fromFailed(
        state: ProcessBrokerLifecycle.Failed,
        event: ProcessBrokerLifecycleEvent,
    ) = when (event) {
        ProcessBrokerLifecycleEvent.CloseRequested -> advanced(ProcessBrokerLifecycle.Closing)
        ProcessBrokerLifecycleEvent.StartRequested,
        ProcessBrokerLifecycleEvent.StartupSucceeded,
        is ProcessBrokerLifecycleEvent.StartupFailed,
        ProcessBrokerLifecycleEvent.CleanupSucceeded,
        is ProcessBrokerLifecycleEvent.CleanupFailed,
        -> violation(state, event)
        }

    private fun advanced(state: ProcessBrokerLifecycle) = ProcessBrokerLifecycleTransition.Advanced(state)

    private fun violation(
        state: ProcessBrokerLifecycle,
        event: ProcessBrokerLifecycleEvent,
    ) = ProcessBrokerLifecycleTransition.ProtocolViolation(state, "invalid broker transition: $state + $event")
}
