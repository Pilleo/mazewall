package io.mazewall.ffi.networking

import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole

public sealed interface SeccompConnectionEvent {
    public data class ListenerReceived(
        val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
    ) : SeccompConnectionEvent

    public data object RecvFailed : SeccompConnectionEvent

    public data object PollIdle : SeccompConnectionEvent

    public data object PollFailed : SeccompConnectionEvent

    public data object AckSucceeded : SeccompConnectionEvent

    public data object AckFailed : SeccompConnectionEvent

    public data object SessionFinished : SeccompConnectionEvent
}

public sealed interface SeccompConnectionEffect {
    public data class RegisterListener(
        val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
    ) : SeccompConnectionEffect

    public data object LogAck : SeccompConnectionEffect

    public data object RunSession : SeccompConnectionEffect
}

public data class SeccompConnectionTransition(
    val connection: SeccompConnection?,
    val effects: List<SeccompConnectionEffect> = emptyList(),
)

/**
 * Pure handshake matrix: [SeccompConnection.Accepted] → [SeccompConnection.FdAttached] →
 * [SeccompConnection.Active] → end. Illegal events stay put with no effects.
 */
public object SeccompConnectionMachine {
    public fun evaluate(
        state: SeccompConnection,
        event: SeccompConnectionEvent,
    ): SeccompConnectionTransition {
        return when (state) {
            is SeccompConnection.Accepted -> accepted(state, event)
            is SeccompConnection.FdAttached -> attached(state, event)
            is SeccompConnection.Active -> active(state, event)
        }
    }

    private fun accepted(
        state: SeccompConnection.Accepted,
        event: SeccompConnectionEvent,
    ): SeccompConnectionTransition {
        return when (event) {
            is SeccompConnectionEvent.ListenerReceived -> SeccompConnectionTransition(
                state.attachFd(event.listenerFd),
                listOf(SeccompConnectionEffect.RegisterListener(event.listenerFd)),
            )
            is SeccompConnectionEvent.PollIdle -> stay(state)
            is SeccompConnectionEvent.RecvFailed,
            is SeccompConnectionEvent.PollFailed,
            -> end()
            is SeccompConnectionEvent.AckSucceeded,
            is SeccompConnectionEvent.AckFailed,
            is SeccompConnectionEvent.SessionFinished,
            -> stay(state)
        }
    }

    private fun attached(
        state: SeccompConnection.FdAttached,
        event: SeccompConnectionEvent,
    ): SeccompConnectionTransition {
        return when (event) {
            is SeccompConnectionEvent.AckSucceeded -> SeccompConnectionTransition(
                state.handshakeComplete(),
                listOf(SeccompConnectionEffect.LogAck),
            )
            is SeccompConnectionEvent.AckFailed -> end()
            is SeccompConnectionEvent.ListenerReceived,
            is SeccompConnectionEvent.RecvFailed,
            is SeccompConnectionEvent.PollIdle,
            is SeccompConnectionEvent.PollFailed,
            is SeccompConnectionEvent.SessionFinished,
            -> stay(state)
        }
    }

    private fun active(
        state: SeccompConnection.Active,
        event: SeccompConnectionEvent,
    ): SeccompConnectionTransition {
        return when (event) {
            is SeccompConnectionEvent.SessionFinished -> SeccompConnectionTransition(
                null,
                listOf(SeccompConnectionEffect.RunSession),
            )
            is SeccompConnectionEvent.ListenerReceived,
            is SeccompConnectionEvent.RecvFailed,
            is SeccompConnectionEvent.PollIdle,
            is SeccompConnectionEvent.PollFailed,
            is SeccompConnectionEvent.AckSucceeded,
            is SeccompConnectionEvent.AckFailed,
            -> stay(state)
        }
    }

    private fun stay(state: SeccompConnection) = SeccompConnectionTransition(state)

    private fun end() = SeccompConnectionTransition(null)
}
