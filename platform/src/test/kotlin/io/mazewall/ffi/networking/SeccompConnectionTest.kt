package io.mazewall.ffi.networking

import io.mazewall.*
import io.mazewall.enforcer.api.*
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.openPath
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.OpenFlags
import io.mazewall.platform.lifecycle.TestFileDescriptors
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.socket.RealSocketManager
import io.mazewall.platform.socket.SocketManager
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import io.mazewall.platform.lifecycle.ForeignFdGuard as FdGuardImport

@org.junit.jupiter.api.extension.ExtendWith(io.mazewall.platform.lifecycle.ForeignFdGuard::class)
@org.junit.jupiter.api.parallel.Isolated
class SeccompConnectionTest {
    private val ownedDescriptors = mutableListOf<FileDescriptor<*, FdState.Open, FdOwnership.Owned>>()

    private fun <R : FileDescriptorRole> owned(role: R): FileDescriptor<R, FdState.Open, FdOwnership.Owned> =
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                when (val result = openPath("/dev/null", OpenFlags.RDONLY)) {
                    is LinuxNative.SyscallResult.Success ->
                        TestFileDescriptors
                        .adopt(result.value.toInt(), role)
                        .also { ownedDescriptors += it }
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
