package io.mazewall.ffi.networking

import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.SocketManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import io.mazewall.core.ForeignFdGuard as FdGuardImport

@org.junit.jupiter.api.extension.ExtendWith(io.mazewall.core.ForeignFdGuard::class)
class SeccompConnectionTest {
    @Test
    fun `closing active connection after socket ownership transfer closes only listener`() {
        val closedDescriptors = mutableListOf<Int>()
        val socketManager = object : SocketManager {
            override fun createUnixServer(socketPath: String) = error("unused")

            override fun accept(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>) = error("unused")

            override fun connect(socketPath: String) = error("unused")

            override fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership>) = error("unused")

            override fun sendDescriptor(
                socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership>,
                fdToSend: FileDescriptor<*, FdState.Open, FdOwnership>,
            ) = error("unused")

            override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>) {
                closedDescriptors += fd.value
            }
        }
        val connection = SeccompConnection
            .Accepted(FileDescriptor.replace(10))
            .apply {
            this.socketManager = socketManager
        }.attachFd(FileDescriptor.replace(20))
            .handshakeComplete()

        connection.markSocketClosed()
        connection.close()

        assertEquals(listOf(20), closedDescriptors)
    }
}
