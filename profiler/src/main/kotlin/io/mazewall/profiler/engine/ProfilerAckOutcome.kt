package io.mazewall.profiler.engine

/** Private profiler protocol, independent of supervisor authorization verdicts. */
internal sealed interface ProfilerAckOutcome {
    data class Acknowledged(
        val shutdown: Boolean,
    ) : ProfilerAckOutcome

    data object PassThrough : ProfilerAckOutcome

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
            if (PASS_THROUGH_COMMAND_BYTE in bytes) return PassThrough
            if (PROTOCOL_ACK_BYTE in bytes) return Acknowledged(SHUTDOWN_COMMAND_BYTE in bytes)
            return Rejected(ProfilerHandshakeFailure.Shutdown)
        }
    }
}

internal enum class ProfilerHandshakeFailure {
    Timeout,
    Transport,
    MalformedReply,
    Shutdown,
}
