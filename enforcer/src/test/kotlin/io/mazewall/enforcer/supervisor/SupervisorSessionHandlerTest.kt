package io.mazewall.enforcer.supervisor

import io.mazewall.core.Arch
import io.mazewall.ffi.IoctlCommand
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.ffi.memory.writeInt
import io.mazewall.ffi.memory.writeLong
import io.mazewall.platform.seccomp.daemon.LoopAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SupervisorSessionHandlerTest {
    @Test
    fun `reactor adapter yields across control IO and leaves reply to the terminal lease`() {
        val fixture = SupervisorBoundaryFixture()
        NativeArena.ofConfined().use { arena ->
            with(arena) {
            fixture.handler().use { handler ->
                handler
                    .beginNotification(
                    arena.allocate(Layouts.SECCOMP_NOTIF).also(::notification),
                    arena.allocate(Layouts.SECCOMP_NOTIF_RESP),
                    fixture.listener,
                    fixture.socket,
                    io.mazewall.core.Deadline
                        .afterMillis(30_000),
                ).use { work ->
                    var progress: io.mazewall.platform.seccomp.daemon.NotificationProgress
                    do {
                        progress = work.poll()
                    } while (progress is io.mazewall.platform.seccomp.daemon.NotificationProgress.Await)
                    val ready = progress as io.mazewall.platform.seccomp.daemon.NotificationProgress.Ready
                    assertEquals(emptyList<KernelReply>(), fixture.replies)
                    assertEquals(1, fixture.writeCount)
                    assertEquals(1, fixture.readCount)
                    assertEquals(io.mazewall.platform.seccomp.UserNotifReplyResult.Sent, ready.reply())
                    assertEquals(listOf(KernelReply(42, 0, 1, false)), fixture.replies)
                }
            }
        }
        }
    }

    @Test
    fun `notification receive retries EINTR and sends exactly one confirmed reply`() {
        val fixture = SupervisorBoundaryFixture()
        var receives = 0
        val replies = fixture.engine.onIoctl
        fixture.engine.onIoctl = { fd, command, buffer ->
            if (command == IoctlCommand.SECCOMP_IOCTL_NOTIF_RECV.code) {
                if (++receives == 1) {
                    SupervisorBoundaryFixture.failure(4)
                } else {
                    notification(buffer)
                    SupervisorBoundaryFixture.success(0)
                }
            } else {
                replies(fd, command, buffer)
            }
        }
        NativeArena.ofConfined().use { arena ->
            val poll = arena.allocate(Layouts.POLLFD, 2)
            PollFdSegment.of(poll.asSlice(0, Layouts.POLLFD_SIZE)).setRevents(NativeConstants.POLLIN)
            val result = with(arena) { fixture.handler().handleActiveListener(poll, arena.allocate(Layouts.SECCOMP_NOTIF), arena.allocate(Layouts.SECCOMP_NOTIF_RESP)) }
            assertEquals(LoopAction.Continue, result)
            PollFdSegment.of(poll.asSlice(0, Layouts.POLLFD_SIZE)).setRevents(NativeConstants.POLLIN)
            with(arena) { fixture.handler().handleActiveListener(poll, arena.allocate(Layouts.SECCOMP_NOTIF), arena.allocate(Layouts.SECCOMP_NOTIF_RESP)) }
        }
        assertEquals(2, receives)
        assertEquals(listOf(KernelReply(42, 0, 1, false)), fixture.replies)
    }

    @Test
    fun `native send failure terminates instead of falling through into a second reply`() {
        val fixture = SupervisorBoundaryFixture()
        fixture.replyResult = SupervisorBoundaryFixture.failure(13)
        NativeArena.ofConfined().use { arena ->
            val notification = arena.allocate(Layouts.SECCOMP_NOTIF).also(::notification)
            assertFalse(fixture.handler().processNotification(notification, arena.allocate(Layouts.SECCOMP_NOTIF_RESP)))
        }
        assertEquals(1, fixture.replies.size)
        assertEquals(1, fixture.sockets.closeCalledCount.get())
    }

    @Test
    fun `request write failure is answered once with EPERM before session termination`() {
        val fixture = SupervisorBoundaryFixture()
        fixture.engine.memory.onWrite = { _, _, _ -> SupervisorBoundaryFixture.failure(13) }
        NativeArena.ofConfined().use { arena ->
            assertFalse(fixture.handler().processNotification(arena.allocate(Layouts.SECCOMP_NOTIF).also(::notification), arena.allocate(Layouts.SECCOMP_NOTIF_RESP)))
        }
        assertEquals(listOf(KernelReply(42, -1, 0, false)), fixture.replies)
    }

    @Test
    fun `VM errors propagate without being mistaken for policy denials`() {
        val fixture = SupervisorBoundaryFixture()
        fixture.engine.memory.onWrite = { _, _, _ -> throw OutOfMemoryError("test heap exhaustion") }
        NativeArena.ofConfined().use { arena ->
            assertThrows(OutOfMemoryError::class.java) {
                fixture.handler().processNotification(arena.allocate(Layouts.SECCOMP_NOTIF).also(::notification), arena.allocate(Layouts.SECCOMP_NOTIF_RESP))
            }
        }
        assertEquals(0, fixture.replies.size)
    }

    @Test
    fun `post-terminal verdict observations cannot emit another reply`() {
        val fixture = SupervisorBoundaryFixture()
        NativeArena.ofConfined().use { arena ->
            val handled = with(arena) { fixture.handler().readAndHandleJvmResponse(fixture.context(arena), SupervisorSessionState.Replied) }
            assertFalse(handled)
        }
        assertEquals(0, fixture.replies.size)
    }

    private fun notification(buffer: ManagedSegment) {
        buffer.writeLong(0, 42)
        buffer.writeInt(8, 1)
        buffer.writeInt(16, Arch.AMD64.clone)
        buffer.writeInt(20, Arch.AMD64.audit)
    }
}
