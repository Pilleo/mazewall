package io.mazewall

import io.mazewall.core.FdOwnership
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.SocketManager
import io.mazewall.core.TestFileDescriptors
import java.util.concurrent.atomic.AtomicInteger

public class MockSocketManager : SocketManager {
    public var connectCalled: Boolean = false
    public var lastConnectPath: String? = null
    public var closeCalledCount: AtomicInteger = AtomicInteger(0)

    override fun createUnixServer(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, io.mazewall.core.FdState.Open, FdOwnership.Owned> =
        TestFileDescriptors
            .replace(10)

    override fun accept(
        serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, io.mazewall.core.FdState.Open, FdOwnership.Owned>,
    ): FileDescriptor<FileDescriptorRole.UnixSocket, io.mazewall.core.FdState.Open, FdOwnership.Owned> =
        TestFileDescriptors
        .replace(11)

    override fun connect(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, io.mazewall.core.FdState.Open, FdOwnership.Owned> {
        connectCalled = true
        lastConnectPath = socketPath
        return TestFileDescriptors
            .replace(12)
    }

    override fun close(fd: FileDescriptor<*, io.mazewall.core.FdState.Open, FdOwnership.Owned>) {
        closeCalledCount.incrementAndGet()
    }

    override fun recvDescriptor(
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, io.mazewall.core.FdState.Open, FdOwnership>,
    ): FileDescriptor<FileDescriptorRole.SeccompNotif, io.mazewall.core.FdState.Open, FdOwnership.Owned>? = null

    override fun sendDescriptor(
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, io.mazewall.core.FdState.Open, FdOwnership>,
        fdToSend: FileDescriptor<*, io.mazewall.core.FdState.Open, FdOwnership>,
    ): Boolean = true
}
