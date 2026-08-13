package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeMemory
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.ffi.memory.writeByte
import io.mazewall.platform.seccomp.daemon.LoopAction
import io.mazewall.platform.seccomp.daemon.NotifResult
import io.mazewall.platform.seccomp.daemon.SeccompNotifHandler
import io.mazewall.platform.seccomp.daemon.SeccompSessionHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class SeccompSessionHandlerTest {
    @Test
    fun `control socket read retries EINTR without terminating the session`() {
        var readCalls = 0
        val memory = object : MockNativeMemory() {
            override fun read(
                fd: FileDescriptor<*, FdState.Open>,
                buf: ManagedSegment,
                count: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                readCalls++
                if (readCalls == 1) {
                    return LinuxNative.SyscallResult.Error(NativeConstants.EINTR, -1L)
                }
                buf.writeByte(0L, 0x01)
                return LinuxNative.SyscallResult.Success(1L)
            }
        }
        val engine = MockNativeEngine(memory = memory)
        val notifHandler = object : SeccompNotifHandler {
            context(arena: NativeArena)
            override fun processNotification(
                notif: ManagedSegment,
                resp: ManagedSegment,
                listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
                socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
            ): NotifResult = NotifResult.HANDLED
        }
        val handler = SeccompSessionHandler(
            socketFd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(10),
            listenerFd = FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(11),
            notifHandler = notifHandler,
            engine = engine,
        )

        handler.use {
            NativeArena.ofConfined().use { arena ->
                val pollFds = arena.allocate(Layouts.POLLFD, 2)
                val socketPollFd = PollFdSegment.of(pollFds.asSlice(Layouts.POLLFD_SIZE, Layouts.POLLFD_SIZE))
                socketPollFd.setFd(10)
                socketPollFd.setRevents(NativeConstants.POLLIN)

                val action = with(arena) { handler.handleActiveListener(pollFds) }

                assertEquals(LoopAction.Continue, action)
                assertEquals(2, readCalls)
                assertFalse(handler.isTerminated)
            }
        }
    }
}
