package io.mazewall.platform.seccomp.daemon

import io.mazewall.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.ffi.IoctlCommand
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.ffi.memory.writeLong
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.TestFileDescriptors
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.socket.SocketManager
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SeccompSessionHandlerTest {
    @Test
    fun `only a lone kernel producer HUP certifies normal stream completion`() {
        val hup = NativeConstants.POLLHUP.toInt()
        val errors = listOf(NativeConstants.POLLERR.toInt(), NativeConstants.POLLNVAL.toInt(), hup or NativeConstants.POLLERR.toInt(), hup or NativeConstants.POLLIN.toInt())
        for ((revents, expected) in listOf(hup to listOf(SeccompStreamEnd.PRODUCER_EXIT)) + errors.map { it to emptyList() }) {
            val ended = mutableListOf<SeccompStreamEnd>()
            val handler = SeccompSessionHandler(
                TestFileDescriptors.replace(10),
                TestFileDescriptors.replace(11),
                object : SeccompNotifHandler {
                    override fun endStream(reason: SeccompStreamEnd) {
                        ended += reason
                    }

                    context(arena: NativeArena) override fun beginNotification(
                        notif: ManagedSegment,
                        resp: ManagedSegment,
                        listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
                        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
                        deadline: Deadline,
                    ): NotificationWork = error("Terminal listener must not start a notification")
                },
                engine = MockNativeEngine(),
                socketManager = recordingSocketManager {},
            )
            handler.use {
                NativeArena.ofConfined().use { arena ->
                    val fds = arena.allocate(Layouts.POLLFD_SIZE * 2)
                    PollFdSegment.of(fds).setRevents(revents.toShort())
                    assertEquals(LoopAction.Break, with(arena) { handler.handleActiveListener(fds) })
                    assertEquals(expected, ended)
                }
            }
        }
    }

    @Test
    fun `completion wakeup advances pending work before its fallback poll`() {
        var replyAttempts = 0
        var readyReplies = 0
        var completionWakeup: NotificationCompletionWakeup? = null
        val wakeup = object : SessionCompletionWakeup {
            override val pollFd: Int = 12
            var drainCount: Int = 0

            override fun signal() = Unit

            override fun drain() {
                drainCount += 1
            }

            override fun close() = Unit
        }
        val engine = MockNativeEngine().apply {
            onIoctl = { _, command, buffer ->
                when (command) {
                    IoctlCommand.SECCOMP_IOCTL_NOTIF_RECV.code -> {
                        buffer.writeLong(Layouts.SECCOMP_NOTIF_ID_OFFSET, 1L)
                        LinuxNative.SyscallResult.Success(0L)
                    }
                    IoctlCommand.SECCOMP_IOCTL_NOTIF_SEND.code -> {
                        replyAttempts += 1
                        LinuxNative.SyscallResult.Success(0L)
                    }
                    else -> error("unexpected ioctl command: $command")
                }
            }
        }
        val handler = SeccompSessionHandler(
            socketFd = TestFileDescriptors.replace<FileDescriptorRole.UnixSocket>(10),
            listenerFd = TestFileDescriptors.replace<FileDescriptorRole.SeccompNotif>(11),
            notifHandler = object : SeccompNotifHandler {
                context(arena: NativeArena) override fun beginNotification(
                    notif: ManagedSegment,
                    resp: ManagedSegment,
                    listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
                    socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
                    deadline: io.mazewall.platform.lifecycle.Deadline,
                ): NotificationWork =
                    object : NotificationWork {
                    private var polls = 0

                    override fun bindCompletionWakeup(wakeup: NotificationCompletionWakeup) {
                        completionWakeup = wakeup
                    }

                    context(arena: NativeArena) override fun poll(): NotificationProgress =
                        if (++polls == 1) {
                            NotificationProgress.Await
                        } else {
                            NotificationProgress.Ready(
                            reply = {
                                readyReplies += 1
                                io.mazewall.platform.seccomp.UserNotifReplyResult.Sent
                            },
                        )
                        }
                }
            },
            engine = engine,
            socketManager = recordingSocketManager {},
            nanoTime = { 0L },
            providedCompletionWakeup = wakeup,
        )

        NativeArena.ofConfined().use { arena ->
            val pollFds = arena.allocate(Layouts.POLLFD_SIZE * 3)
            val listenerPollFd = PollFdSegment.of(pollFds.asSlice(0L, Layouts.POLLFD_SIZE))
            val socketPollFd = PollFdSegment.of(pollFds.asSlice(Layouts.POLLFD_SIZE, Layouts.POLLFD_SIZE))
            val wakeupPollFd = PollFdSegment.of(pollFds.asSlice(Layouts.POLLFD_SIZE * 2, Layouts.POLLFD_SIZE))
            listenerPollFd.setFd(11)
            listenerPollFd.setEvents(NativeConstants.POLLIN)
            socketPollFd.setFd(10)
            socketPollFd.setEvents(NativeConstants.POLLIN)
            wakeupPollFd.setFd(wakeup.pollFd)
            wakeupPollFd.setEvents(NativeConstants.POLLIN)
            listenerPollFd.setRevents(NativeConstants.POLLIN)

            assertEquals(LoopAction.Continue, with(arena) { handler.handleActiveListener(pollFds) })
            assertEquals(50, handler.pollTimeoutMillis())
            checkNotNull(completionWakeup).signal()

            listenerPollFd.setRevents(0)
            wakeupPollFd.setRevents(NativeConstants.POLLIN)
            assertEquals(LoopAction.Continue, with(arena) { handler.handleActiveListener(pollFds) })
        }

        assertEquals(1, wakeup.drainCount)
        assertEquals(0, replyAttempts)
        assertEquals(1, readyReplies)
        assertEquals(SeccompCleanupOutcome.Complete, handler.closeWithResult())
    }

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
            socketFd = TestFileDescriptors
                .replace<FileDescriptorRole.UnixSocket>(10),
            listenerFd = TestFileDescriptors
                .replace<FileDescriptorRole.SeccompNotif>(11),
            notifHandler = object : SeccompNotifHandler {
                context(arena: NativeArena) override fun beginNotification(
                    notif: ManagedSegment,
                    resp: ManagedSegment,
                    listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
                    socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
                    deadline: io.mazewall.platform.lifecycle.Deadline,
                ): NotificationWork {
                    notificationHandlerCalls += 1
                    return object : NotificationWork {
                        context(arena: NativeArena) override fun poll(): NotificationProgress =
                            NotificationProgress.Ready(
                            { io.mazewall.platform.seccomp.UserNotifReplyResult.Sent },
                            NotifResult.PASS_THROUGH,
                        )
                    }
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
