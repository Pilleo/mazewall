package io.mazewall.ffi.networking

import io.mazewall.LinuxNative
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.OpenFlags
import io.mazewall.core.RealSocketManager
import io.mazewall.core.SocketManager
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.openPath
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import io.mazewall.core.ForeignFdGuard as FdGuardImport

@org.junit.jupiter.api.extension.ExtendWith(io.mazewall.core.ForeignFdGuard::class)
@org.junit.jupiter.api.parallel.Isolated
class SeccompConnectionTest {
    private val ownedDescriptors = mutableListOf<FileDescriptor<*, FdState.Open, FdOwnership.Owned>>()

    private fun <R : FileDescriptorRole> owned(role: R): FileDescriptor<R, FdState.Open, FdOwnership.Owned> =
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                when (val result = openPath("/dev/null", OpenFlags.RDONLY)) {
                    is LinuxNative.SyscallResult.Success -> FileDescriptor.adopt(result.value.toInt(), role).also { ownedDescriptors += it }
                    is LinuxNative.SyscallResult.Error -> error("Cannot open owned test descriptor: $result")
                }
            }
        }

    @AfterEach
    fun closeOwnedFixtures() {
        ownedDescriptors.forEach(RealSocketManager::close)
    }

    @Test
    fun `cancelled close propagates and listener closes before retrying socket`() {
        val closedDescriptors = mutableListOf<Int>()
        val cancellation = java.util.concurrent.CancellationException("cancelled close")
        val socket = owned(FileDescriptorRole.UnixSocket)
        val listener = owned(FileDescriptorRole.SeccompNotif)
        val connection = SeccompConnection
            .Accepted(socket)
            .apply {
            socketManager = object : SocketManager by unusedSocketManager() {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>) {
                    closedDescriptors += fd.value
                    if (closedDescriptors.size == 1) throw cancellation
                }
            }
        }.attachFd(listener)

        val thrown = org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.CancellationException::class.java) {
            connection.closeWithResult()
        }
        org.junit.jupiter.api.Assertions
            .assertSame(cancellation, thrown)
        assertEquals(listOf(socket.value, listener.value), closedDescriptors)
        assertEquals(SeccompConnection.CloseResult.Closed, connection.closeWithResult())
        assertEquals(listOf(socket.value, listener.value, socket.value), closedDescriptors)
    }

    @Test
    fun `socket manager injection is write once`() {
        val connection = SeccompConnection.Accepted(owned(FileDescriptorRole.UnixSocket))
        connection.socketManager = unusedSocketManager()
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            connection.socketManager = unusedSocketManager()
        }
    }

    @Test
    fun `failed close remains observable and retries ownership`() {
        var attempts = 0
        val connection = SeccompConnection.Accepted(owned(FileDescriptorRole.UnixSocket)).apply {
            socketManager = object : SocketManager {
                override fun createUnixServer(socketPath: String) = error("unused")

                override fun accept(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>) = error("unused")

                override fun connect(socketPath: String) = error("unused")

                override fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership>) = error("unused")

                override fun sendDescriptor(
                    socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership>,
                    fdToSend: FileDescriptor<*, FdState.Open, FdOwnership>,
                ) = error("unused")

                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>) {
                    attempts++
                    if (attempts == 1) throw IllegalStateException("transient")
                }
            }
        }

        assertTrue(connection.closeWithResult() is SeccompConnection.CloseResult.Failed)
        assertEquals(SeccompConnection.CloseResult.Closed, connection.closeWithResult())
        assertEquals(2, attempts)
    }

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
        val listener = owned(FileDescriptorRole.SeccompNotif)
        val connection = SeccompConnection
            .Accepted(owned(FileDescriptorRole.UnixSocket))
            .apply {
            this.socketManager = socketManager
        }.attachFd(listener)
            .handshakeComplete()

        connection.markSocketClosed()
        connection.close()

        assertEquals(listOf(listener.value), closedDescriptors)
    }

    private fun unusedSocketManager(): SocketManager =
        object : SocketManager {
        override fun createUnixServer(socketPath: String) = error("unused")

        override fun accept(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>) = error("unused")

        override fun connect(socketPath: String) = error("unused")

        override fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership>) = error("unused")

        override fun sendDescriptor(
            socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership>,
            fdToSend: FileDescriptor<*, FdState.Open, FdOwnership>,
        ) = error("unused")

        override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>) = Unit
    }
}
