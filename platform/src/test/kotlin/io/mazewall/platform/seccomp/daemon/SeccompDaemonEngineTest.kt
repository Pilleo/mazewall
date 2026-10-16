package io.mazewall.platform.seccomp.daemon

import io.mazewall.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.networking.SeccompConnection
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.Deadline
import io.mazewall.platform.lifecycle.TestFileDescriptors
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SeccompDaemonEngineTest {
    @Test
    fun `accepted control socket is requested nonblocking`() {
        var acceptedFlags = 0
        val engine = MockNativeEngine().apply {
            networking.onAccept4 = { _, _, _, flags ->
                acceptedFlags = flags
                LinuxNative.SyscallResult.Error(NativeConstants.EAGAIN, -1L)
            }
        }
        val daemon = SeccompDaemonEngine(
            socketPath = "/tmp/test.sock",
            notifHandlerFactory = { _, _ -> error("unused") },
            engine = engine,
        )

        daemon.handleNewConnection(TestFileDescriptors.replace<FileDescriptorRole.UnixSocket>(9))

        assertTrue((acceptedFlags and NativeConstants.O_NONBLOCK.toInt()) != 0)
    }

    @Test
    fun `handshake acknowledgement makes one attempt after each poll`() {
        var writes = 0
        val nativeEngine = MockNativeEngine()
        val daemon = SeccompDaemonEngine(
            socketPath = "/tmp/test.sock",
            notifHandlerFactory = { _, _ -> error("unused") },
            engine = nativeEngine,
            handshakeWriter = { _, _, _ ->
                writes++
                LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(NativeConstants.EINTR, -1L)
            },
        )
        val socketFd = TestFileDescriptors.replace<FileDescriptorRole.UnixSocket>(10)

        NativeArena.ofConfined().use { arena ->
            val result = daemon.writeHandshakeAck(
                socketFd,
                arena.allocate(1),
                Deadline.afterMillis(10_000),
            )
            assertEquals(HandshakeWriteOutcome.RetryAfterPoll, result)
        }
        assertEquals(1, writes)
    }

    @Test
    fun `short handshake acknowledgement fails closed`() {
        val daemon = SeccompDaemonEngine(
            socketPath = "/tmp/test.sock",
            notifHandlerFactory = { _, _ -> error("unused") },
            handshakeWriter = { _, _, _ ->
                LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
            },
        )
        NativeArena.ofConfined().use { arena ->
            assertEquals(
                HandshakeWriteOutcome.Failed(NativeConstants.EIO),
                daemon.writeHandshakeAck(
                    TestFileDescriptors.replace<FileDescriptorRole.UnixSocket>(10),
                    arena.allocate(1),
                    Deadline.afterMillis(10_000),
                ),
            )
        }
    }

    @Test
    fun `handshake deadlines distinguish connections that reuse an fd number`() {
        val deadlines = HandshakeDeadlineRegistry()
        val socket = TestFileDescriptors.replace<FileDescriptorRole.UnixSocket>(10)
        val first = SeccompConnection.Accepted(socket)
        val second = SeccompConnection.Accepted(socket)

        val firstDeadline = deadlines.getOrCreate(first) { Deadline(100) }
        val secondDeadline = deadlines.getOrCreate(second) { Deadline(200) }
        deadlines.remove(first)

        assertEquals(100, firstDeadline.nanoTime)
        assertEquals(200, secondDeadline.nanoTime)
        assertEquals(1, deadlines.size)
    }

    @Test
    fun `expired handshake acknowledgement does not invoke writer`() {
        var writes = 0
        val daemon = SeccompDaemonEngine(
            socketPath = "/tmp/test.sock",
            notifHandlerFactory = { _, _ -> error("unused") },
            handshakeWriter = { _, _, _ ->
                writes++
                LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(1)
            },
        )
        val socketFd = TestFileDescriptors.replace<FileDescriptorRole.UnixSocket>(10)

        NativeArena.ofConfined().use { arena ->
            val result = daemon.writeHandshakeAck(
                socketFd,
                arena.allocate(1),
                Deadline(System.nanoTime() - 1),
            )
            assertEquals(HandshakeWriteOutcome.TimedOut, result)
        }
        assertEquals(0, writes)
    }

    @Test
    fun `session reactor closes factory-created notification handler`() {
        var notificationHandlerClosed = false
        val nativeEngine = MockNativeEngine().apply {
            onPoll = { _, _, _ -> LinuxNative.SyscallResult.Error(NativeConstants.EPERM, -1L) }
        }
        val daemon = SeccompDaemonEngine(
            socketPath = "/tmp/test.sock",
            notifHandlerFactory = { _, _ ->
                object : SeccompNotifHandler, AutoCloseable {
                    context(arena: NativeArena) override fun beginNotification(
                        notif: ManagedSegment,
                        resp: ManagedSegment,
                        listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
                        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
                        deadline: io.mazewall.platform.lifecycle.Deadline,
                    ): NotificationWork = error("unused")

                    override fun close() {
                        notificationHandlerClosed = true
                    }
                }
            },
            engine = nativeEngine,
        )
        val socketFd = TestFileDescriptors
            .replace<FileDescriptorRole.UnixSocket>(10)
        val listenerFd = TestFileDescriptors
            .replace<FileDescriptorRole.SeccompNotif>(11)
        val connection = SeccompConnection.Active(socketFd, listenerFd)

        NativeArena.ofConfined().use { arena ->
            daemon.processConnectionStep(arena, connection, socketFd, arena.allocate(8L))
        }

        assertTrue(notificationHandlerClosed)
    }
}
