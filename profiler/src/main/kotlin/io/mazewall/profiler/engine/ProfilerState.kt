package io.mazewall.profiler.engine

/**
 * States representing the lifecycle of a profiler connection/session.
 */
internal sealed interface ProfilerState {
    /** Connection accepted, waiting to receive the seccomp listener file descriptor. */
    data class Connected(
        val socketFd: Int,
    ) : ProfilerState

    /** FD received, sending PROTOCOL_ACK_BYTE to parent. */
    data class HandshakeAck(
        val socketFd: Int,
        val listenerFd: Int,
    ) : ProfilerState

    /** Actively polling for seccomp notifications or shutdown command. */
    data class ActiveSession(
        val socketFd: Int,
        val listenerFd: Int,
    ) : ProfilerState

    /** Seccomp notification received, sending trace event. */
    data class Notified(
        val socketFd: Int,
        val listenerFd: Int,
        val notifId: Long,
        val event: TraceEvent,
    ) : ProfilerState

    /** Event sent, waiting for PROTOCOL_ACK_BYTE or SHUTDOWN from parent. */
    data class WaitingForAck(
        val socketFd: Int,
        val listenerFd: Int,
        val notifId: Long,
    ) : ProfilerState

    /** Closed state. */
    data object Terminated : ProfilerState
}
