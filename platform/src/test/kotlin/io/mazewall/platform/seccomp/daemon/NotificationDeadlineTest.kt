package io.mazewall.platform.seccomp.daemon

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.core.Deadline
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.TestFileDescriptors
import io.mazewall.ffi.IoctlCommand
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.ffi.memory.readInt
import io.mazewall.ffi.memory.writeLong
import io.mazewall.platform.seccomp.UserNotifReply
import io.mazewall.platform.seccomp.UserNotifReplyResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NotificationDeadlineTest {
    @Test
    fun `closing pending work denies before retiring reply buffers`() {
        Fixture().use { fixture ->
            fixture.tick()
            assertEquals(SeccompCleanupOutcome.Complete, fixture.handler.closeWithResult())
            assertEquals(listOf(-1), fixture.replies)
            assertEquals(LoopAction.Break, fixture.tick())
        }
    }

    @Test
    fun `successful reply is sent once across repeated reactor ticks`() {
        Fixture().use { fixture ->
            fixture.ready = true
            assertEquals(LoopAction.Continue, fixture.tick())
            assertEquals(LoopAction.Continue, fixture.tick())
            assertEquals(listOf(0), fixture.replies)
            assertEquals(1, fixture.closes)
        }
    }

    @Test
    fun `throwing terminal operation cannot enter denial recovery`() {
        Fixture().use { fixture ->
            fixture.ready = true
            fixture.throwOnSend = true
            assertThrows<IllegalStateException> { fixture.tick() }
            assertEquals(LoopAction.Break, fixture.tick())
            assertEquals(listOf(0), fixture.replies)
        }
    }

    @Test
    fun `idle reactor expires pending work at thirty seconds and ignores late completion`() {
        val fixture = Fixture()
        fixture.use {
            assertEquals(LoopAction.Continue, fixture.tick())
            fixture.now = 29_999_999_999L
            assertEquals(LoopAction.Continue, fixture.tick())
            assertEquals(0, fixture.replies.size)
            fixture.now = 30_000_000_000L
            assertEquals(LoopAction.Break, fixture.tick())
            assertEquals(listOf(-1), fixture.replies)
            assertEquals(1, fixture.closes)
            fixture.ready = true
            assertEquals(LoopAction.Break, fixture.tick())
            assertEquals(listOf(-1), fixture.replies)
            assertEquals(0, fixture.completions)
        }
    }

    @Test
    fun `deadline wins over a completion returned at its boundary`() {
        Fixture().use { fixture ->
            fixture.ready = true
            fixture.duringPoll = { fixture.now = 30_000_000_000L }
            assertEquals(LoopAction.Break, fixture.tick())
            assertEquals(listOf(-1), fixture.replies)
            assertEquals(0, fixture.completions)
        }
    }

    @Test
    fun `notification gone and hard failure are terminal without denial retry`() {
        for (errno in listOf(NativeConstants.ENOENT, NativeConstants.EIO)) {
            Fixture().use { fixture ->
                fixture.ready = true
                fixture.replyErrno = errno
                assertEquals(LoopAction.Break, fixture.tick())
                assertEquals(LoopAction.Break, fixture.tick())
                assertEquals(listOf(0), fixture.replies)
                assertEquals(1, fixture.completions)
            }
        }
    }

    @Test
    fun `timeout attempts denial once even when send is interrupted`() {
        Fixture().use { fixture ->
            fixture.tick()
            fixture.now = 30_000_000_000L
            fixture.replyErrno = NativeConstants.EINTR
            assertEquals(LoopAction.Break, fixture.tick())
            assertEquals(listOf(-1), fixture.replies)
        }
    }

    @Test
    fun `receive interruptions have a finite budget`() {
        Fixture().use { fixture ->
            fixture.receiveInterrupted = true
            assertEquals(LoopAction.Break, fixture.tick())
            assertEquals(8, fixture.receives)
            assertTrue(fixture.replies.isEmpty())
        }
    }

    private class Fixture : AutoCloseable {
        var now = 0L
        var ready = false
        var receiveInterrupted = false
        var receives = 0
        var closes = 0
        var completions = 0
        var replyErrno = 0
        var throwOnSend = false
        var duringPoll: () -> Unit = {}
        val replies = mutableListOf<Int>()
        val arena = NativeArena.ofConfined()
        val pollFds = arena.allocate(Layouts.POLLFD, 2)
        val engine = MockNativeEngine().apply {
            onIoctl = { _, command, buffer ->
                when (command) {
                    IoctlCommand.SECCOMP_IOCTL_NOTIF_RECV.code -> {
                        receives++
                        buffer.writeLong(0L, 42L)
                        if (receiveInterrupted) {
                            LinuxNative.SyscallResult.Error(NativeConstants.EINTR, -1L)
                        } else {
                            LinuxNative.SyscallResult.Success(0L)
                        }
                    }
                    IoctlCommand.SECCOMP_IOCTL_NOTIF_SEND.code -> {
                        replies += buffer.readInt(Layouts.SECCOMP_NOTIF_RESP_ERROR_OFFSET)
                        if (throwOnSend) throw IllegalStateException("native reply failed")
                        if (replyErrno == 0) {
                            LinuxNative.SyscallResult.Success(0L)
                        } else {
                            LinuxNative.SyscallResult.Error(replyErrno, -1L)
                        }
                    }
                    else -> error("Unexpected ioctl")
                }
            }
        }
        val handler = SeccompSessionHandler(
            TestFileDescriptors.replace<FileDescriptorRole.UnixSocket>(10),
            TestFileDescriptors.replace<FileDescriptorRole.SeccompNotif>(11),
            object : SeccompNotifHandler {
                context(arena: NativeArena) override fun beginNotification(
                    notif: ManagedSegment,
                    resp: ManagedSegment,
                    listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
                    socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
                    deadline: Deadline,
                ): NotificationWork =
                    object : NotificationWork {
                    context(arena: NativeArena) override fun poll(): NotificationProgress {
                        duringPoll()
                        return if (ready) {
                            NotificationProgress.Ready(
                            reply = {
                                completions++
                                UserNotifReply.encodeContinue(resp, 42L)
                                UserNotifReply.send(engine.raw, listenerFd, resp)
                            },
                        )
                        } else {
                            NotificationProgress.Await
                        }
                    }

                    override fun close() {
                        closes++
                    }
                }
            },
            engine = engine,
            nanoTime = { now },
        )

        init {
            PollFdSegment.of(pollFds.asSlice(0L, Layouts.POLLFD_SIZE)).setRevents(NativeConstants.POLLIN)
        }

        fun tick(): LoopAction =
            with(arena) {
            val result = handler.handleActiveListener(pollFds)
            PollFdSegment.of(pollFds.asSlice(0L, Layouts.POLLFD_SIZE)).setRevents(0)
            result
        }

        override fun close() {
            handler.close()
            arena.close()
        }
    }
}
