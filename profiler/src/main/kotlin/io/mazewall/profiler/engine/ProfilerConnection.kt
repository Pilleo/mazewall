package io.mazewall.profiler.engine

import io.mazewall.LinuxNative
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole

/**
 * Represents the lifecycle of a connection between the Profiler Daemon and the parent JVM.
 */
internal sealed class ProfilerConnection {
    abstract val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>

    /** Initial state: Connection accepted, waiting to receive the seccomp listener FD. */
    data class Accepted(
        override val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
    ) : ProfilerConnection() {
        fun attachFd(listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>) = FdAttached(socketFd, listenerFd)
    }

    /** Intermediate state: Listener FD received, waiting to send the 0xAC ACK byte. */
    data class FdAttached(
        override val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
    ) : ProfilerConnection() {
        fun handshakeComplete() = Active(socketFd, listenerFd)
    }

    /** Established state: Handshake complete, session is now active and polling. */
    data class Active(
        override val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
    ) : ProfilerConnection()
}
