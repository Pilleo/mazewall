package io.mazewall.profiler.engine

import io.mazewall.LinuxNative

/**
 * Represents the lifecycle of a connection between the Profiler Daemon and the parent JVM.
 */
internal sealed class ProfilerConnection {
    abstract val socketFd: LinuxNative.FileDescriptor

    /** Initial state: Connection accepted, waiting to receive the seccomp listener FD. */
    data class Accepted(
        override val socketFd: LinuxNative.FileDescriptor,
    ) : ProfilerConnection() {
        fun attachFd(listenerFd: LinuxNative.FileDescriptor) = FdAttached(socketFd, listenerFd)
    }

    /** Intermediate state: Listener FD received, waiting to send the 0xAC ACK byte. */
    data class FdAttached(
        override val socketFd: LinuxNative.FileDescriptor,
        val listenerFd: LinuxNative.FileDescriptor,
    ) : ProfilerConnection() {
        fun handshakeComplete() = Active(socketFd, listenerFd)
    }

    /** Established state: Handshake complete, session is now active and polling. */
    data class Active(
        override val socketFd: LinuxNative.FileDescriptor,
        val listenerFd: LinuxNative.FileDescriptor,
    ) : ProfilerConnection()
}
