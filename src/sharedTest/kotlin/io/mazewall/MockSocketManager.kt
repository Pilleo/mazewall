package io.mazewall.enforcer.api

import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.TestFileDescriptors
import io.mazewall.platform.native.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.socket.SocketManager
import io.mazewall.platform.syscall.*
import java.util.concurrent.atomic.AtomicInteger

internal class MockSocketManager : SocketManager {
    public var connectCalled: Boolean = false
    public var lastConnectPath: String? = null
    public var closeCalledCount: AtomicInteger = AtomicInteger(0)

    override fun createUnixServer(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, io.mazewall.platform.fd.FdState.Open, FdOwnership.Owned> =
        TestFileDescriptors
            .replace(10)

    override fun accept(
        serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, io.mazewall.platform.fd.FdState.Open, FdOwnership.Owned>,
    ): FileDescriptor<FileDescriptorRole.UnixSocket, io.mazewall.platform.fd.FdState.Open, FdOwnership.Owned> =
        TestFileDescriptors
        .replace(11)

    override fun connect(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, io.mazewall.platform.fd.FdState.Open, FdOwnership.Owned> {
        connectCalled = true
        lastConnectPath = socketPath
        return TestFileDescriptors
            .replace(12)
    }

    override fun close(fd: FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, FdOwnership.Owned>) {
        closeCalledCount.incrementAndGet()
    }

    override fun recvDescriptor(
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, io.mazewall.platform.fd.FdState.Open, FdOwnership>,
    ): FileDescriptor<FileDescriptorRole.SeccompNotif, io.mazewall.platform.fd.FdState.Open, FdOwnership.Owned>? = null

    override fun sendDescriptor(
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, io.mazewall.platform.fd.FdState.Open, FdOwnership>,
        fdToSend: FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, FdOwnership>,
    ): Boolean = true
}
