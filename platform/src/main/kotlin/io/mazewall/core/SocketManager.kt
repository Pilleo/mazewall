package io.mazewall.core

import io.mazewall.LinuxNative
import io.mazewall.getFdOrThrow
import io.mazewall.onFailure
import io.mazewall.ffi.memory.ManagedSegment

/**
 * Interface for socket creation and connection handling.
 * Decoupling this allows for mocking socket interactions in tests.
 */
public interface SocketManager {
    public fun createUnixServer(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>

    public fun accept(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>

    public fun connect(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>

    public fun close(fd: FileDescriptor<*, FdState.Open>)

    public fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>?

    /**
     * Receives an `SCM_RIGHTS` descriptor and adopts it under [role].
     * Daemon handshake uses [FileDescriptorRole.SeccompNotif]; broker grants use [FileDescriptorRole.Granted].
     */
    public fun <R : FileDescriptorRole> recvDescriptor(
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        role: R,
    ): FileDescriptor<R, FdState.Open>? =
        io.mazewall.ffi.networking.SupervisorSocketUtils.recvDescriptor(socketFd, role)

    public fun sendDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>, fdToSend: FileDescriptor<*, FdState.Open>): Boolean
}

/**
 * Real implementation of [SocketManager] using [LinuxNative].
 */
public object RealSocketManager : SocketManager {
    override fun createUnixServer(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> {
        val fd = LinuxNative.networking.socket(
            io.mazewall.ffi.networking.SupervisorSocketUtils.AF_UNIX,
            io.mazewall.ffi.networking.SupervisorSocketUtils.SOCK_STREAM or io.mazewall.ffi.NativeConstants.SOCK_CLOEXEC,
            0
        ).getFdOrThrow("socket(AF_UNIX)").let { FileDescriptor.unixSocket(it.value) }

        io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
            val sockaddrUn = io.mazewall.ffi.networking.SupervisorSocketUtils.setupSockAddrUn(arena, socketPath)
            val sockaddrManaged = sockaddrUn.managed

            LinuxNative.networking.bind(fd, sockaddrManaged, io.mazewall.ffi.networking.SupervisorSocketUtils.SOCKADDR_UN_SIZE)
                .onFailure { _, _ ->
                    LinuxNative.fileSystem.close(fd)
                }.getOrThrow("bind(AF_UNIX)")
        }

        LinuxNative.networking.listen(fd, io.mazewall.ffi.networking.SupervisorSocketUtils.BACKLOG_SIZE)
            .onFailure { _, _ ->
                LinuxNative.fileSystem.close(fd)
            }.getOrThrow("listen")

        return fd
    }

    override fun accept(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> {
        val res = LinuxNative.networking.accept4(
            serverFd,
            ManagedSegment.NULL,
            ManagedSegment.NULL,
            io.mazewall.ffi.NativeConstants.SOCK_CLOEXEC
        )
        return res.getFdOrThrow("accept").let { FileDescriptor.adopt(it.value, FileDescriptorRole.UnixSocket) }
    }

    override fun connect(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> {
        val fdVal = io.mazewall.ffi.networking.SupervisorSocketUtils.connectWithRetry(socketPath)
        return FileDescriptor.adopt(fdVal, FileDescriptorRole.UnixSocket)
    }

    override fun close(fd: FileDescriptor<*, FdState.Open>) {
        LinuxNative.fileSystem.close(fd)
    }

    override fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>? {
        return recvDescriptor(socketFd, FileDescriptorRole.SeccompNotif)
    }

    override fun <R : FileDescriptorRole> recvDescriptor(
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        role: R,
    ): FileDescriptor<R, FdState.Open>? {
        return io.mazewall.ffi.networking.SupervisorSocketUtils.recvDescriptor(socketFd, role)
    }

    override fun sendDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>, fdToSend: FileDescriptor<*, FdState.Open>): Boolean {
        return io.mazewall.ffi.networking.SupervisorSocketUtils.sendDescriptor(socketFd.value, fdToSend.value)
    }
}
