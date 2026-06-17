package io.mazewall.profiler.engine

import io.mazewall.LinuxNative
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole

/**
 * States representing the lifecycle of a profiler connection/session.
 */
internal sealed interface ProfilerState {
    /** Connection accepted, waiting to receive the seccomp listener file descriptor. */
    data class Connected(
        val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
    ) : ProfilerState

    /** FD received, sending PROTOCOL_ACK_BYTE to parent. */
    data class HandshakeAck(
        val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
    ) : ProfilerState

    /** Actively polling for seccomp notifications or shutdown command. */
    data class ActiveSession(
        val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
    ) : ProfilerState

    /** Seccomp notification received, sending trace event. */
    data class Notified(
        val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
        val notifId: Long,
        val event: SyscallEvent<SyscallEventState.Resolved>,
    ) : ProfilerState

    /** Event sent, waiting for PROTOCOL_ACK_BYTE or SHUTDOWN from parent. */
    data class WaitingForAck(
        val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
        val notifId: Long,
    ) : ProfilerState

    /** Closed state. */
    data object Terminated : ProfilerState
}
