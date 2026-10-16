package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.api.MockNativeMemory
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.ffi.memory.writeByte
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
import io.mazewall.platform.seccomp.daemon.LoopAction
import io.mazewall.platform.seccomp.daemon.NotifResult
import io.mazewall.platform.seccomp.daemon.SeccompNotifHandler
import io.mazewall.platform.seccomp.daemon.SeccompSessionHandler
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class SeccompSessionHandlerTest {
    @Test
    fun `control socket read retries EINTR without terminating the session`() {
        var readCalls = 0
        val memory = object : MockNativeMemory() {
            override fun read(
                fd: FileDescriptor<*, FdState.Open, FdOwnership>,
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
            context(arena: NativeArena) override fun beginNotification(
                notif: ManagedSegment,
                resp: ManagedSegment,
                listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
                socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
                deadline: io.mazewall.platform.lifecycle.Deadline,
            ): io.mazewall.platform.seccomp.daemon.NotificationWork = error("unused")
        }
        val handler = SeccompSessionHandler(
            socketFd = TestFileDescriptors
                .replace<FileDescriptorRole.UnixSocket>(10),
            listenerFd = TestFileDescriptors
                .replace<FileDescriptorRole.SeccompNotif>(11),
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
