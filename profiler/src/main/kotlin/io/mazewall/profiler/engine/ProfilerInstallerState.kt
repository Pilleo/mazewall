package io.mazewall.profiler.engine

import io.mazewall.LinuxNative
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole

/**
 * States representing the installation and socket handshake process.
 */
internal sealed interface ProfilerInstallerState {
    /** Setup hasn't started. */
    data object Uninitialized : ProfilerInstallerState

    /** Main thread is building and installing the Seccomp BPF filter. */
    data object InstallingBpf : ProfilerInstallerState

    /** BPF installed; coordinator is connecting to socket path. */
    data class Connecting(
        val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
    ) : ProfilerInstallerState

    /** Connected; coordinator is sending the listener FD to the daemon. */
    data class SendingDescriptor(
        val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
        val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
    ) : ProfilerInstallerState

    /** Descriptor sent; coordinator is waiting for verification ACK. */
    data class VerifyingAck(
        val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
        val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
    ) : ProfilerInstallerState

    /** Handshake verified; trace listener started. */
    data class Active(
        val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
        val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
    ) : ProfilerInstallerState

    /** Installation or handshake failed; cleaning up descriptors and propagating error. */
    data class Failed(
        val error: Throwable,
    ) : ProfilerInstallerState
}
