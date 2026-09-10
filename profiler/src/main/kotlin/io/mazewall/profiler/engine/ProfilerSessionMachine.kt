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

    /** A notification was intentionally continued without publishing a trace event. */
    data object NoisePathBypassed : ProfilerSessionEvent

    /** Socket or notification I/O failed before this session could complete its protocol step. */
    data object TransportFailed : ProfilerSessionEvent

    /** A terminal session was observed again; modeled as a benign no-op for the reactor. */
    data object PostTerminalObserved : ProfilerSessionEvent
}

internal data class ProfilerSessionTransition(
    val state: ProfilerState,
    val terminate: Boolean = false,
    val passThrough: Boolean = false,
    val protocolViolation: Boolean = false,
)

internal object ProfilerSessionMachine {
    fun evaluate(
        state: ProfilerState,
        event: ProfilerSessionEvent,
    ): ProfilerSessionTransition {
        if (event is ProfilerSessionEvent.TransportFailed) {
            return ProfilerSessionTransition(
                ProfilerState.Terminated(state.socketFd, state.listenerFd),
                terminate = true,
            )
        }
        return when (state) {
            is ProfilerState.ActiveSession -> when (event) {
                is ProfilerSessionEvent.NotificationReceived ->
                    ProfilerSessionTransition(state.notified(event.id, event.event))
                is ProfilerSessionEvent.NoisePathBypassed ->
                    ProfilerSessionTransition(state, passThrough = true)
                is ProfilerSessionEvent.EventDelivered,
                is ProfilerSessionEvent.AckSucceeded,
                is ProfilerSessionEvent.HandshakeFailed,
                is ProfilerSessionEvent.PassedThrough,
                is ProfilerSessionEvent.TransportFailed,
                is ProfilerSessionEvent.PostTerminalObserved,
                -> violation(state)
            }
            is ProfilerState.Notified -> when (event) {
                is ProfilerSessionEvent.EventDelivered ->
                    ProfilerSessionTransition(state.waitingForAck())
                is ProfilerSessionEvent.HandshakeFailed ->
                    ProfilerSessionTransition(state.terminate(), terminate = true)
                is ProfilerSessionEvent.NotificationReceived,
                is ProfilerSessionEvent.AckSucceeded,
                is ProfilerSessionEvent.PassedThrough,
                is ProfilerSessionEvent.NoisePathBypassed,
                is ProfilerSessionEvent.TransportFailed,
                is ProfilerSessionEvent.PostTerminalObserved,
                -> violation(state)
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
                is ProfilerSessionEvent.NoisePathBypassed,
                is ProfilerSessionEvent.TransportFailed,
                is ProfilerSessionEvent.PostTerminalObserved,
                -> violation(state)
            }
            is ProfilerState.Terminated -> ProfilerSessionTransition(state)
            is ProfilerState.PassThrough,
            is ProfilerState.Connected,
            is ProfilerState.HandshakeAck,
            -> violation(state)
        }
    }

    private fun violation(state: ProfilerState) =
        ProfilerSessionTransition(
            ProfilerState.Terminated(state.socketFd, state.listenerFd),
            terminate = true,
            protocolViolation = true,
        )
}
