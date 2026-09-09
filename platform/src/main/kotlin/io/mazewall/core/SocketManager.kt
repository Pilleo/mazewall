package io.mazewall.core

import io.mazewall.LinuxNative
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.getFdOrThrow

/**
 * Interface for socket creation and connection handling.
 * Decoupling this allows for mocking socket interactions in tests.
 */
public interface SocketManager {
    public fun createUnixServer(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>

    public fun accept(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>

    public fun connect(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>

    /** Native result failures are terminal; other thrown failures must precede native dispatch. */
    public fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>)

    public fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership>): FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>?

    /**
     * Receives an `SCM_RIGHTS` descriptor and adopts it under [role].
     * Daemon handshake uses [FileDescriptorRole.SeccompNotif]; broker grants use [FileDescriptorRole.Granted].
     */
    public fun <R : FileDescriptorRole> recvDescriptor(
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership>,
        role: R,
    ): FileDescriptor<R, FdState.Open, FdOwnership.Owned>? =
        io.mazewall.ffi.networking.SupervisorSocketUtils
        .recvDescriptor(socketFd, role)

    public fun sendDescriptor(
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership>,
        fdToSend: FileDescriptor<*, FdState.Open, FdOwnership>,
    ): Boolean
}

/** A native close result failed; the integer may already be reused and must never be retried. */
@io.mazewall.MazewallInternal
public class NativeCloseFailure internal constructor(
    message: String,
) : IllegalStateException(message)

/**
 * Real implementation of [SocketManager] using [LinuxNative].
 */
public object RealSocketManager : SocketManager {
    override fun createUnixServer(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> =
        io.mazewall.ffi.networking.UnixServerSocket
        .open(socketPath)

    override fun accept(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> {
        val res = LinuxNative.networking.accept4(
            serverFd,
            ManagedSegment.NULL,
            ManagedSegment.NULL,
            io.mazewall.ffi.NativeConstants.SOCK_CLOEXEC,
        )
        return res.getFdOrThrow("accept").let { FileDescriptor.adopt(it.value, FileDescriptorRole.UnixSocket) }
    }

    override fun connect(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> {
        val fdVal = io.mazewall.ffi.networking.SupervisorSocketUtils
            .connectWithRetry(socketPath)
        return FileDescriptor.adopt(fdVal, FileDescriptorRole.UnixSocket)
    }

    override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>) {
        when (val result = LinuxNative.fileSystem.close(fd)) {
            is LinuxNative.SyscallResult.Success -> Unit
            is LinuxNative.SyscallResult.Error -> throw NativeCloseFailure("close(fd=${fd.value}) failed: $result")
        }
    }

    override fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership>): FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>? {
        return recvDescriptor(socketFd, FileDescriptorRole.SeccompNotif)
    }

    override fun <R : FileDescriptorRole> recvDescriptor(
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership>,
        role: R,
    ): FileDescriptor<R, FdState.Open, FdOwnership.Owned>? {
        return io.mazewall.ffi.networking.SupervisorSocketUtils
            .recvDescriptor(socketFd, role)
    }

    override fun sendDescriptor(
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership>,
        fdToSend: FileDescriptor<*, FdState.Open, FdOwnership>,
    ): Boolean {
        return io.mazewall.ffi.networking.SupervisorSocketUtils
            .sendDescriptor(socketFd.value, fdToSend.value)
    }
}
