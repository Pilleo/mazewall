package io.mazewall.profiler.engine

import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole

/**
 * States representing the lifecycle of the Profiler Daemon server.
 */
internal sealed interface ProfilerDaemonState {
    /** The daemon server has not been started. */
    data object Uninitialized : ProfilerDaemonState {
        fun listening(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>, socketPath: String) =
            Listening(serverFd, socketPath)
    }

    /** The daemon server is creating and binding the socket. */
    data class Listening(
        val serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        val socketPath: String,
    ) : ProfilerDaemonState {
        fun active() = Active(serverFd)
    }

    /** The daemon server is actively listening and processing client connections. */
    data class Active(
        val serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
    ) : ProfilerDaemonState

    /** Global shutdown has been triggered. */
    data object ShuttingDown : ProfilerDaemonState

    /** Teardown finished, all descriptors closed. */
    data object Terminated : ProfilerDaemonState
}
