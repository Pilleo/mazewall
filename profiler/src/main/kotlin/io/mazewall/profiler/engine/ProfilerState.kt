package io.mazewall.profiler.engine

import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.memory.ManagedSegment

/**
 * States representing the lifecycle of a profiler connection/session.
 *
 * Each state defines the valid operations (events) that can occur in that state,
 * ensuring type-safe transitions.
 *
 * ### Functional State Pattern
 * These states are designed to be used in a stateless reducer loop. Transitions
 * are explicit methods that return the next valid state, preventing invalid
 * protocol sequences at compile-time.
 */
internal sealed interface ProfilerState {
    /** The Unix Domain Socket used for IPC with the tracee. */
    val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>?

    /** The seccomp USER_NOTIF listener descriptor received from the tracee. */
    val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>?

    /** Initial state: Connection accepted, waiting to receive the seccomp listener FD. */
    data class Connected(
        override val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
    ) : ProfilerState {
        override val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>? = null

        /** Transitions to [HandshakeAck] once the listener FD is received via SCM_RIGHTS. */
        fun attachFd(fd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>) =
            HandshakeAck(socketFd, fd)
    }

    /** FD received, sending PROTOCOL_ACK_BYTE to parent. */
    data class HandshakeAck(
        override val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        override val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
    ) : ProfilerState {
        /** Transitions to [ActiveSession] after the handshake ACK is sent. */
        fun handshakeComplete() = ActiveSession(socketFd, listenerFd)
    }

    /** Actively polling for seccomp notifications or shutdown command. */
    data class ActiveSession(
        override val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        override val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
    ) : ProfilerState {
        /** Transitions to [Notified] when a syscall is trapped by the kernel. */
        fun notified(id: Long, event: SyscallEvent<SyscallEventState.Resolved>) =
            Notified(socketFd, listenerFd, id, event)

        /** Gracefully terminates the session. */
        fun terminate() = Terminated(socketFd, listenerFd)
    }

    /** Seccomp notification received, sending trace event. */
    data class Notified(
        override val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        override val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
        val notifId: Long,
        val event: SyscallEvent<SyscallEventState.Resolved>,
    ) : ProfilerState {
        /** Transitions to [WaitingForAck] after the trace event is published to the JVM. */
        fun waitingForAck() = WaitingForAck(socketFd, listenerFd, notifId)

        /** Terminates on protocol error. */
        fun terminate() = Terminated(socketFd, listenerFd)
    }

    /** Event sent, waiting for PROTOCOL_ACK_BYTE or SHUTDOWN from parent. */
    data class WaitingForAck(
        override val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        override val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
        val notifId: Long,
    ) : ProfilerState {
        /** Transitions back to [ActiveSession] after the JVM acknowledges receipt. */
        fun acknowledged() = ActiveSession(socketFd, listenerFd)

        /** Terminates on timeout or desynchronization. */
        fun terminate() = Terminated(socketFd, listenerFd)
    }

    /** Pass-through mode: ignoring all events and just sending CONTINUE blindly. */
    data class PassThrough(
        override val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>?,
        override val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>?,
    ) : ProfilerState

    /** Closed state. No further operations are allowed. */
    data class Terminated(
        override val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>? = null,
        override val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>? = null,
    ) : ProfilerState
}
