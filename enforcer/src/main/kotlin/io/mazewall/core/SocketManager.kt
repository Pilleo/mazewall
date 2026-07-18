package io.mazewall.core

import io.mazewall.LinuxNative
import io.mazewall.getFdOrThrow
import io.mazewall.onFailure
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

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

    public fun sendDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>, fdToSend: FileDescriptor<*, FdState.Open>): Boolean
}

/**
 * Real implementation of [SocketManager] using [LinuxNative].
 */
public object RealSocketManager : SocketManager {
    override fun createUnixServer(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> {
        val fd = LinuxNative.withTransaction {
            LinuxNative.networking.socket(
                io.mazewall.ffi.networking.SupervisorSocketUtils.AF_UNIX,
                io.mazewall.ffi.networking.SupervisorSocketUtils.SOCK_STREAM,
                0
            )
        }.getFdOrThrow("socket(AF_UNIX)").let { FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(it.value) }

        Arena.ofConfined().use { arena ->
            val sockaddrUn = io.mazewall.ffi.networking.SupervisorSocketUtils.setupSockAddrUn(arena, socketPath)

            LinuxNative.withTransaction {
                LinuxNative.networking.bind(fd, sockaddrUn.segment, io.mazewall.ffi.networking.SupervisorSocketUtils.SOCKADDR_UN_SIZE)
            }.onFailure { _, _ ->
                LinuxNative.fileSystem.close(fd)
            }.getOrThrow("bind(AF_UNIX)")
        }

        LinuxNative.withTransaction {
            LinuxNative.networking.listen(fd, io.mazewall.ffi.networking.SupervisorSocketUtils.BACKLOG_SIZE)
        }.onFailure { _, _ ->
            LinuxNative.fileSystem.close(fd)
        }.getOrThrow("listen")

        return fd
    }

    override fun accept(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> {
        val res = LinuxNative.withTransaction {
            LinuxNative.networking.accept(serverFd, MemorySegment.NULL, MemorySegment.NULL)
        }
        return res.getFdOrThrow("accept").let { FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(it.value) }
    }

    override fun connect(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> {
        val fdVal = io.mazewall.ffi.networking.SupervisorSocketUtils.connectWithRetry(socketPath)
        return FileDescriptor.unsafe(fdVal)
    }

    override fun close(fd: FileDescriptor<*, FdState.Open>) {
        LinuxNative.fileSystem.close(fd)
    }

    override fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>? {
        return io.mazewall.ffi.networking.SupervisorSocketUtils.recvDescriptor(socketFd)
    }

    override fun sendDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>, fdToSend: FileDescriptor<*, FdState.Open>): Boolean {
        return io.mazewall.ffi.networking.SupervisorSocketUtils.sendDescriptor(socketFd.value, fdToSend.value)
    }
}
