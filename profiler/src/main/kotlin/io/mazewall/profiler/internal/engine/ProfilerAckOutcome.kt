package io.mazewall.profiler.internal.engine

/** Private profiler protocol, independent of supervisor authorization verdicts. */
internal sealed interface ProfilerAckOutcome {
    data class Acknowledged(
        val action: Action,
    ) : ProfilerAckOutcome

    enum class Action {
        Continue,
        PassThrough,
        Shutdown,
        ;

        fun withPendingPassThrough(requested: Boolean): Action =
            when (this) {
                Continue -> if (requested) PassThrough else Continue
                PassThrough, Shutdown -> this
            }
    }

    data object PassThroughRequested : ProfilerAckOutcome

    data class Rejected(
        val reason: ProfilerHandshakeFailure,
    ) : ProfilerAckOutcome

    companion object {
        fun decode(bytes: ByteArray): ProfilerAckOutcome {
            if (bytes.isEmpty() ||
                bytes.size > ACK_BUF_SIZE ||
                bytes.any {
                    it != PROTOCOL_ACK_BYTE && it != PASS_THROUGH_COMMAND_BYTE && it != SHUTDOWN_COMMAND_BYTE
                }
            ) {
                return Rejected(ProfilerHandshakeFailure.MalformedReply)
            }
            if (PROTOCOL_ACK_BYTE in bytes) {
                return Acknowledged(
                    when {
                        SHUTDOWN_COMMAND_BYTE in bytes -> Action.Shutdown
                        PASS_THROUGH_COMMAND_BYTE in bytes -> Action.PassThrough
                        else -> Action.Continue
                    },
                )
            }
            return if (SHUTDOWN_COMMAND_BYTE in bytes) Rejected(ProfilerHandshakeFailure.Shutdown) else PassThroughRequested
        }
    }
}

internal enum class ProfilerHandshakeFailure {
    Timeout,
    Transport,
    MalformedReply,
    Shutdown,
}
