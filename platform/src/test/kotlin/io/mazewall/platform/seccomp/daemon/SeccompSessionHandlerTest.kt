package io.mazewall.platform.seccomp.daemon

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.SocketManager
import io.mazewall.ffi.IoctlCommand
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.ffi.memory.writeLong
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SeccompSessionHandlerTest {
    @Test
    fun `expired pass-through notification terminates the session without another reply`() {
        var receiveAttempts = 0
        var replyAttempts = 0
        var notificationHandlerCalls = 0
        var closedSockets = 0
        val engine = MockNativeEngine().apply {
            onIoctl = { _, command, buffer ->
                when (command) {
                    IoctlCommand.SECCOMP_IOCTL_NOTIF_RECV.code -> {
                        receiveAttempts += 1
                        buffer.writeLong(Layouts.SECCOMP_NOTIF_ID_OFFSET, receiveAttempts.toLong())
                        LinuxNative.SyscallResult.Success(0L)
                    }
                    IoctlCommand.SECCOMP_IOCTL_NOTIF_SEND.code -> {
                        replyAttempts += 1
                        LinuxNative.SyscallResult.Error(NativeConstants.ENOENT, -1L)
                    }
                    else -> error("unexpected ioctl command: $command")
                }
            }
        }
        val handler = SeccompSessionHandler(
            socketFd = FileDescriptor.replace<FileDescriptorRole.UnixSocket>(10),
            listenerFd = FileDescriptor.replace<FileDescriptorRole.SeccompNotif>(11),
            notifHandler = object : SeccompNotifHandler {
                context(arena: NativeArena) override fun processNotification(
                    notif: ManagedSegment,
                    resp: ManagedSegment,
                    listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
                    socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
                ): NotifResult {
                    notificationHandlerCalls += 1
                    return NotifResult.PASS_THROUGH
                }
            },
            engine = engine,
            socketManager = recordingSocketManager { closedSockets += 1 },
        )

        NativeArena.ofConfined().use { arena ->
            val pollFds = arena.allocate(Layouts.POLLFD_SIZE * 2)
            val listenerPollFd = PollFdSegment.of(pollFds.asSlice(0L, Layouts.POLLFD_SIZE))
            listenerPollFd.setFd(11)
            listenerPollFd.setRevents(NativeConstants.POLLIN)
            PollFdSegment.of(pollFds.asSlice(Layouts.POLLFD_SIZE, Layouts.POLLFD_SIZE)).setFd(10)

            assertEquals(LoopAction.Continue, with(arena) { handler.handleActiveListener(pollFds) })
            assertFalse(handler.isTerminated)
            assertEquals(1, notificationHandlerCalls)
            assertEquals(1, closedSockets)

            assertEquals(LoopAction.Break, with(arena) { handler.handleActiveListener(pollFds) })
            assertTrue(handler.isTerminated)
            assertEquals(2, receiveAttempts)
            assertEquals(1, replyAttempts)
            assertEquals(1, notificationHandlerCalls)

            assertEquals(LoopAction.Break, with(arena) { handler.handleActiveListener(pollFds) })
            assertEquals(2, receiveAttempts)
            assertEquals(1, replyAttempts)
        }

        assertEquals(SeccompCleanupOutcome.Complete, handler.closeWithResult())
        assertEquals(SeccompCleanupOutcome.Complete, handler.closeWithResult())
    }

    private fun recordingSocketManager(onClose: () -> Unit): SocketManager =
        object : SocketManager {
        override fun createUnixServer(socketPath: String) = error("unused")

        override fun accept(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>) = error("unused")

        override fun connect(socketPath: String) = error("unused")

        override fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership>) = error("unused")

        override fun sendDescriptor(
            socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership>,
            fdToSend: FileDescriptor<*, FdState.Open, FdOwnership>,
        ) = error("unused")

        override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>) = onClose()
    }
}
