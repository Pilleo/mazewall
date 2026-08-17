package io.mazewall.profiler.engine

internal sealed interface ProfilerSessionEvent {
    data class NotificationReceived(
        val id: Long,
        val event: SyscallEvent<SyscallEventState.Resolved>,
    ) : ProfilerSessionEvent
    data object EventDelivered : ProfilerSessionEvent
    data object AckSucceeded : ProfilerSessionEvent
    data object HandshakeFailed : ProfilerSessionEvent
    data object PassedThrough : ProfilerSessionEvent
}

internal data class ProfilerSessionTransition(
    val state: ProfilerState,
    val terminate: Boolean = false,
    val passThrough: Boolean = false,
)

internal object ProfilerSessionMachine {
    fun evaluate(state: ProfilerState, event: ProfilerSessionEvent): ProfilerSessionTransition {
        return when (state) {
            is ProfilerState.ActiveSession -> when (event) {
                is ProfilerSessionEvent.NotificationReceived ->
                    ProfilerSessionTransition(state.notified(event.id, event.event))
                is ProfilerSessionEvent.EventDelivered,
                is ProfilerSessionEvent.AckSucceeded,
                is ProfilerSessionEvent.HandshakeFailed,
                is ProfilerSessionEvent.PassedThrough,
                -> stay(state)
            }
            is ProfilerState.Notified -> when (event) {
                is ProfilerSessionEvent.EventDelivered ->
                    ProfilerSessionTransition(state.waitingForAck())
                is ProfilerSessionEvent.HandshakeFailed ->
                    ProfilerSessionTransition(state.terminate(), terminate = true)
                is ProfilerSessionEvent.NotificationReceived,
                is ProfilerSessionEvent.AckSucceeded,
                is ProfilerSessionEvent.PassedThrough,
                -> stay(state)
            }
            is ProfilerState.WaitingForAck -> when (event) {
                is ProfilerSessionEvent.AckSucceeded ->
                    ProfilerSessionTransition(state.acknowledged())
                is ProfilerSessionEvent.PassedThrough ->
                    ProfilerSessionTransition(state.acknowledged(), passThrough = true)
                is ProfilerSessionEvent.HandshakeFailed ->
                    ProfilerSessionTransition(state.terminate(), terminate = true)
                is ProfilerSessionEvent.NotificationReceived,
                is ProfilerSessionEvent.EventDelivered,
                -> stay(state)
            }
            is ProfilerState.Terminated,
            is ProfilerState.PassThrough,
            is ProfilerState.Connected,
            is ProfilerState.HandshakeAck,
            -> stay(state)
        }
    }

    private fun stay(state: ProfilerState) = ProfilerSessionTransition(state)
}
