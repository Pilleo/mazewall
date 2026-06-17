package io.mazewall.profiler.engine

import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import java.lang.foreign.MemorySegment

/**
 * States representing the lifecycle of a profiler connection/session.
 *
 * Each state defines the valid operations (events) that can occur in that state,
 * ensuring type-safe transitions.
 */
internal sealed interface ProfilerState {
    val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>?
    val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>?

    /** Initial state: Connection accepted, waiting to receive the seccomp listener FD. */
    data class Connected(
        override val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
    ) : ProfilerState {
        override val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>? = null

        fun attachFd(fd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>) =
            HandshakeAck(socketFd, fd)
    }

    /** FD received, sending PROTOCOL_ACK_BYTE to parent. */
    data class HandshakeAck(
        override val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        override val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
    ) : ProfilerState {
        fun handshakeComplete() = ActiveSession(socketFd, listenerFd)
    }

    /** Actively polling for seccomp notifications or shutdown command. */
    data class ActiveSession(
        override val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        override val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
    ) : ProfilerState {
        fun notified(id: Long, event: SyscallEvent<SyscallEventState.Resolved>) =
            Notified(socketFd, listenerFd, id, event)

        fun terminate() = Terminated(socketFd, listenerFd)
    }

    /** Seccomp notification received, sending trace event. */
    data class Notified(
        override val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        override val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
        val notifId: Long,
        val event: SyscallEvent<SyscallEventState.Resolved>,
    ) : ProfilerState {
        fun waitingForAck() = WaitingForAck(socketFd, listenerFd, notifId)
        fun terminate() = Terminated(socketFd, listenerFd)
    }

    /** Event sent, waiting for PROTOCOL_ACK_BYTE or SHUTDOWN from parent. */
    data class WaitingForAck(
        override val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        override val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
        val notifId: Long,
    ) : ProfilerState {
        fun acknowledged() = ActiveSession(socketFd, listenerFd)
        fun terminate() = Terminated(socketFd, listenerFd)
    }

    /** Closed state. */
    data class Terminated(
        override val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>? = null,
        override val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>? = null,
    ) : ProfilerState
}
