package io.mazewall.profiler.internal.engine

import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.native.LinuxNative

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
        val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
    ) : ProfilerInstallerState

    /** Connected; coordinator is sending the listener FD to the daemon. */
    data class SendingDescriptor(
        val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
        val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    ) : ProfilerInstallerState

    /** Descriptor sent; coordinator is waiting for verification ACK. */
    data class VerifyingAck(
        val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
        val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    ) : ProfilerInstallerState

    /** Handshake verified; trace listener started. */
    data class Active(
        val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
        val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    ) : ProfilerInstallerState

    /** Installation or handshake failed; cleaning up descriptors and propagating error. */
    data class Failed(
        val error: Throwable,
    ) : ProfilerInstallerState
}
