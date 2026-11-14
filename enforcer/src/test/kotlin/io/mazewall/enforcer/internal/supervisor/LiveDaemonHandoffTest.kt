package io.mazewall.enforcer.internal.supervisor

import io.mazewall.ffi.IoctlCommand
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.readInt
import io.mazewall.ffi.memory.readLong
import io.mazewall.ffi.memory.writeLong
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.Deadline
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.RawSyscallOperations
import io.mazewall.platform.native.getFdOrThrow
import io.mazewall.platform.seccomp.UserNotifReplyResult
import io.mazewall.platform.seccomp.daemon.*
import io.mazewall.platform.socket.RealSocketManager
import io.mazewall.platform.socket.SocketManager
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertSame

class LiveDaemonHandoffTest {
    @Test
    fun `Live denial reports a notification that is gone`() {
        assertDenialFailure(NativeConstants.ENOENT, UserNotifReplyResult.NotificationGone)
    }

    @Test
    fun `Live denial reports a reply failure`() {
        assertDenialFailure(NativeConstants.EIO, UserNotifReplyResult.Failed(NativeConstants.EIO))
    }

    private fun assertDenialFailure(
        errno: Int,
        expected: UserNotifReplyResult,
    ) {
        val real = LinuxNative.snapshot()
        val socket = real.networking
            .socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0)
            .getFdOrThrow("Live reply socket", FileDescriptorRole.UnixSocket)
        val listener = real.networking
            .socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0)
            .getFdOrThrow("Live reply listener", FileDescriptorRole.SeccompNotif)
        var sends = 0
        val raw = object : RawSyscallOperations by real.raw {
            override fun <Req, Res> ioctl(
                fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                command: IoctlCommand<Req, Res>,
                arg: Req,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                assertSame(listener, fd)
                assertEquals(IoctlCommand.SECCOMP_IOCTL_NOTIF_SEND.code, command.code)
                sends++
                return LinuxNative.SyscallResult.Error(errno, -1L)
            }
        }
        val native = object : NativeEngine by real {
            override val raw = raw
        }
        try {
            val replies = SupervisorReplyInterpreter(
                native,
                listener,
                SupervisorExecRewriteExchange(native, socket),
                TraceeDescriptorImporter(native),
            )
            NativeArena.ofConfined().use { arena ->
                val notif = arena.allocate(Layouts.SECCOMP_NOTIF)
                val response = arena.allocate(Layouts.SECCOMP_NOTIF_RESP)
                notif.writeLong(Layouts.SECCOMP_NOTIF_ID_OFFSET, 77L)
                val handler = LiveListenerHandler(listener, replies)
                val work = with(arena) {
                    handler.beginNotification(notif, response, listener, socket, Deadline.afterMillis(1000))
                }
                val ready = with(arena) { work.poll() } as NotificationProgress.Ready
                assertEquals(expected, ready.reply())
                assertEquals(77L, response.readLong(Layouts.SECCOMP_NOTIF_RESP_ID_OFFSET))
                assertEquals(-NativeConstants.EPERM, response.readInt(Layouts.SECCOMP_NOTIF_RESP_ERROR_OFFSET))
                assertEquals(0, response.readInt(Layouts.SECCOMP_NOTIF_RESP_FLAGS_OFFSET))
                work.close()
            }
            assertEquals(1, sends)
        } finally {
            real.fileSystem.close(socket)
            real.fileSystem.close(listener)
        }
    }

    @Test
    fun `admitted Live socket selects the original listener and denies notification`() {
        val real = LinuxNative.snapshot()
        val socket = real.networking
            .socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0)
            .getFdOrThrow("Live test socket", FileDescriptorRole.UnixSocket)
        val listener = real.networking
            .socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0)
            .getFdOrThrow("Live test listener", FileDescriptorRole.SeccompNotif)
        val sent = mutableListOf<Long>()
        val admissionCloses = AtomicInteger()
        val admission = object : LiveListenerAdmission {
            override fun close() {
                admissionCloses.incrementAndGet()
            }
        }
        val raw = object : RawSyscallOperations by real.raw {
            override fun poll(
                fds: ManagedSegment,
                nfds: Long,
                timeout: Int,
            ) = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(1L)

            override fun <Req, Res> ioctl(
                fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                command: IoctlCommand<Req, Res>,
                arg: Req,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                assertSame(listener, fd)
                sent.add(command.code)
                return LinuxNative.SyscallResult.Success(0L)
            }
        }
        val native = object : NativeEngine by real {
            override val raw = raw
        }
        val sockets = object : SocketManager by RealSocketManager.capture(real) {
            override fun recvDescriptor(
                socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership>,
            ): FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned> {
                assertSame(socket, socketFd)
                return listener
            }
        }
        var factoryCalls = 0
        var legacyCalls = 0
        lateinit var daemon: SeccompDaemonEngine
        daemon = SeccompDaemonEngine(
            "/unused/live-test.sock",
            notifHandlerFactory = { _, _ ->
                legacyCalls++
                error("Live reached Legacy factory")
            },
            engine = native,
            socketManager = sockets,
            handshakeWriter = { _, _, _ -> LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(1L) },
            hooks = SeccompConnectionHooks(
                beforeListener = { accepted, _ ->
                    assertSame(socket, accepted)
                    PreludeOutcome.LiveListener(admission)
                },
                liveListenerFactory = { actual, actualSocket, actualListener ->
                    factoryCalls++
                    assertSame(admission, actual)
                    assertSame(socket, actualSocket)
                    assertSame(listener, actualListener)
                    val replies = SupervisorReplyInterpreter(
                        native,
                        actualListener,
                        SupervisorExecRewriteExchange(native, actualSocket),
                        TraceeDescriptorImporter(native),
                    )
                    val handler = LiveListenerHandler(actualListener, replies)
                    NativeArena.ofConfined().use { arena ->
                        val notif = arena.allocate(Layouts.SECCOMP_NOTIF)
                        val response = arena.allocate(Layouts.SECCOMP_NOTIF_RESP)
                        notif.writeLong(Layouts.SECCOMP_NOTIF_ID_OFFSET, 77L)
                        val work = with(arena) { handler.beginNotification(notif, response, actualListener, actualSocket, Deadline.afterMillis(1000)) }
                        val result = with(arena) { work.poll() } as NotificationProgress.Ready
                        result.reply()
                        assertEquals(77L, response.readLong(Layouts.SECCOMP_NOTIF_RESP_ID_OFFSET))
                        assertEquals(-1L, response.readLong(Layouts.SECCOMP_NOTIF_RESP_VAL_OFFSET))
                        assertEquals(-NativeConstants.EPERM, response.readInt(Layouts.SECCOMP_NOTIF_RESP_ERROR_OFFSET))
                        assertEquals(0, response.readInt(Layouts.SECCOMP_NOTIF_RESP_FLAGS_OFFSET))
                        work.close()
                    }
                    daemon.triggerGlobalShutdown("synthetic Live test")
                    handler
                },
            ),
        )
        try {
            daemon.handleConnection(socket)
            assertEquals(1, factoryCalls)
            assertEquals(0, legacyCalls)
            assertEquals(listOf(IoctlCommand.SECCOMP_IOCTL_NOTIF_SEND.code), sent)
            assertEquals(1, admissionCloses.get())
        } finally {
            daemon.triggerGlobalShutdown("test cleanup")
            if (socket.isLiveForIo()) real.fileSystem.close(socket)
            if (listener.isLiveForIo()) real.fileSystem.close(listener)
        }
    }
}
