package io.mazewall.ffi.networking

import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole

internal sealed interface SeccompConnectionEvent {
    public data class ListenerReceived(
        val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
    ) : SeccompConnectionEvent

    public data object RecvFailed : SeccompConnectionEvent

    public data object PollIdle : SeccompConnectionEvent

    public data object PollFailed : SeccompConnectionEvent

    public data object AckSucceeded : SeccompConnectionEvent

    public data object AckFailed : SeccompConnectionEvent

    public data object SessionFinished : SeccompConnectionEvent
}

internal sealed interface SeccompConnectionEffect {
    public data class RegisterListener(
        val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
    ) : SeccompConnectionEffect

    public data object LogAck : SeccompConnectionEffect

    public data object RunSession : SeccompConnectionEffect

    /** A descriptor delivered outside the handshake is still owned and must be closed. */
    public data class CloseRejectedListener(
        val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
    ) : SeccompConnectionEffect
}

/** Records why a connection did not advance; callers must not silently ignore protocol violations. */
internal sealed interface SeccompConnectionOutcome {
    public data object Advanced : SeccompConnectionOutcome

    /** A timeout before the handshake starts; retain the accepted socket and poll again. */
    public data object IdlePoll : SeccompConnectionOutcome

    public data class ProtocolViolation(
        val state: SeccompConnection,
        val event: SeccompConnectionEvent,
    ) : SeccompConnectionOutcome

    public data object Terminated : SeccompConnectionOutcome
}

internal data class SeccompConnectionTransition(
    val connection: SeccompConnection?,
    val effects: List<SeccompConnectionEffect> = emptyList(),
    val outcome: SeccompConnectionOutcome,
)

/**
 * Pure handshake matrix: [SeccompConnection.Accepted] → [SeccompConnection.FdAttached] →
 * [SeccompConnection.Active] → end. Only an idle pre-handshake poll is benign; every other
 * out-of-order event terminates the connection as a protocol violation.
 */
internal object SeccompConnectionMachine {
    internal fun evaluate(
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
                SeccompConnectionOutcome.Advanced,
            )
            is SeccompConnectionEvent.PollIdle -> idle(state)
            is SeccompConnectionEvent.RecvFailed,
            is SeccompConnectionEvent.PollFailed,
            -> end()
            is SeccompConnectionEvent.AckSucceeded,
            is SeccompConnectionEvent.AckFailed,
            is SeccompConnectionEvent.SessionFinished,
            -> violation(state, event)
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
                SeccompConnectionOutcome.Advanced,
            )
            is SeccompConnectionEvent.AckFailed -> end()
            is SeccompConnectionEvent.ListenerReceived,
            is SeccompConnectionEvent.RecvFailed,
            is SeccompConnectionEvent.PollIdle,
            is SeccompConnectionEvent.PollFailed,
            is SeccompConnectionEvent.SessionFinished,
            -> violation(state, event)
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
                SeccompConnectionOutcome.Terminated,
            )
            is SeccompConnectionEvent.ListenerReceived,
            is SeccompConnectionEvent.RecvFailed,
            is SeccompConnectionEvent.PollIdle,
            is SeccompConnectionEvent.PollFailed,
            is SeccompConnectionEvent.AckSucceeded,
            is SeccompConnectionEvent.AckFailed,
            -> violation(state, event)
        }
    }

    private fun idle(state: SeccompConnection) = SeccompConnectionTransition(state, outcome = SeccompConnectionOutcome.IdlePoll)

    private fun violation(
        state: SeccompConnection,
        event: SeccompConnectionEvent,
    ): SeccompConnectionTransition {
        val effects = when (event) {
            is SeccompConnectionEvent.ListenerReceived -> listOf(SeccompConnectionEffect.CloseRejectedListener(event.listenerFd))
            is SeccompConnectionEvent.RecvFailed,
            is SeccompConnectionEvent.PollIdle,
            is SeccompConnectionEvent.PollFailed,
            is SeccompConnectionEvent.AckSucceeded,
            is SeccompConnectionEvent.AckFailed,
            is SeccompConnectionEvent.SessionFinished,
            -> emptyList()
        }
        return SeccompConnectionTransition(null, effects, SeccompConnectionOutcome.ProtocolViolation(state, event))
    }

    private fun end() = SeccompConnectionTransition(null, outcome = SeccompConnectionOutcome.Terminated)
}
