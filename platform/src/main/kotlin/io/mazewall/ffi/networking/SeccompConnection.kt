package io.mazewall.ffi.networking

import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole

/**
 * Represents the lifecycle of a connection between a seccomp daemon and the tracee JVM.
 */
public sealed class SeccompConnection {
    public abstract val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>

    /** Initial state: Connection accepted, waiting to receive the seccomp listener FD. */
    public data class Accepted(
        override val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
    ) : SeccompConnection() {
        public fun attachFd(listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>): FdAttached =
            FdAttached(socketFd, listenerFd)
    }

    /** Intermediate state: Listener FD received, waiting to send the 0xAC ACK byte. */
    public data class FdAttached(
        override val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
    ) : SeccompConnection() {
        public fun handshakeComplete(): Active = Active(socketFd, listenerFd)
    }

    /** Established state: Handshake complete, session is now active and polling. */
    public data class Active(
        override val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
    ) : SeccompConnection()
}
