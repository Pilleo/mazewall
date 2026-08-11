package io.mazewall.platform.seccomp.daemon

import io.mazewall.LinuxNative
import io.mazewall.NativeEngine
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.RealSocketManager
import io.mazewall.core.SocketManager
import io.mazewall.ffi.IoctlCommand
import io.mazewall.ffi.IoctlPayload
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.ffi.memory.SegmentPool
import io.mazewall.ffi.memory.fill
import io.mazewall.ffi.memory.readByte
import io.mazewall.ffi.memory.readLong
import io.mazewall.ffi.memory.writeInt
import io.mazewall.ffi.memory.writeLong
import io.mazewall.ffi.typed
import io.mazewall.onSuccess
import io.mazewall.recover

/**
 * Unified reactor handler for active seccomp user notifications and UNIX domain control sockets.
 */
public class SeccompSessionHandler(
    private val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
    private val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
    private val notifHandler: SeccompNotifHandler,
    private val onShutdown: (String) -> Unit = {},
    private val onSocketClosed: () -> Unit = {},
    private val engine: NativeEngine = LinuxNative,
    private val socketManager: SocketManager = RealSocketManager,
) : AutoCloseable {
    private val sessionArena = NativeArena.ofConfined()
    private val ackBuf: ManagedSegment = sessionArena.allocate(1L)
    private val notif: ManagedSegment = SegmentPool.SECCOMP_NOTIF_POOL.rent()
    private val resp: ManagedSegment = SegmentPool.SECCOMP_NOTIF_RESP_POOL.rent()

    private var isPassThrough = false

    public var isTerminated: Boolean = false
        private set

    override fun close() {
        try {
            SegmentPool.SECCOMP_NOTIF_POOL.release(notif)
        } catch (_: Throwable) {}
        try {
            SegmentPool.SECCOMP_NOTIF_RESP_POOL.release(resp)
        } catch (_: Throwable) {}
        sessionArena.close()
    }

    @Suppress("ReturnCount")
    context(arena: NativeArena)
    public fun handleActiveListener(
        pollFds: ManagedSegment,
    ): LoopAction {
        if (isTerminated) {
            return LoopAction.Break
        }

        // pollFds layout:
        // [0]: listenerFd (Layouts.POLLFD_SIZE)
        // [1]: socketFd   (Layouts.POLLFD_SIZE)
        val pfd2 = PollFdSegment.of(pollFds.asSlice(Layouts.POLLFD_SIZE, Layouts.POLLFD_SIZE))
        val socketRevents = pfd2.getRevents().toInt()
        val errorOrHup = NativeConstants.POLLERR.toInt() or NativeConstants.POLLHUP.toInt() or NativeConstants.POLLNVAL.toInt()

        if (!isPassThrough && (socketRevents and (NativeConstants.POLLIN.toInt() or errorOrHup)) != 0) {
            val isDeadOrShutdown = (socketRevents and errorOrHup) != 0 || handleShutdownRequest(ackBuf, pollFds)
            if (isDeadOrShutdown) {
                isTerminated = true
                return LoopAction.Break
            }
        }

        val pfd1 = PollFdSegment.of(pollFds.asSlice(0L, Layouts.POLLFD_SIZE))
        val listenerRevents = pfd1.getRevents().toInt()
        if ((listenerRevents and errorOrHup) != 0) {
            isTerminated = true
            return LoopAction.Break
        }

        if ((listenerRevents and NativeConstants.POLLIN.toInt()) != 0) {
            notif.fill(0)
            var recvRes: LinuxNative.SyscallResult<Long, *>
            while (true) {
                recvRes = engine.raw.ioctl(
                    listenerFd,
                    IoctlCommand.SECCOMP_IOCTL_NOTIF_RECV,
                    notif.typed<IoctlPayload.SeccompNotif>()
                )
                if (recvRes is LinuxNative.SyscallResult.Error<*> && recvRes.errno == NativeConstants.EINTR) {
                    continue
                }
                break
            }

            recvRes.onSuccess {
                if (isPassThrough) {
                    sendSeccompContinue(notif.readLong(0L), resp)
                } else {
                    val result = notifHandler.processNotification(notif, resp, listenerFd, socketFd)
                    when (result) {
                        NotifResult.HANDLED -> {}
                        NotifResult.TERMINATE -> {
                            isTerminated = true
                        }
                        NotifResult.PASS_THROUGH -> {
                            isPassThrough = true
                            val pfd2 = PollFdSegment.of(pollFds.asSlice(Layouts.POLLFD_SIZE, Layouts.POLLFD_SIZE))
                            pfd2.setFd(-1)
                            closeControlSocket()
                        }
                    }
                }
            }
            if (isTerminated) return LoopAction.Break
        }
        return LoopAction.Continue
    }

    private fun handleShutdownRequest(ackBuf: ManagedSegment, pollFds: ManagedSegment): Boolean {
        val res = engine.memory.read(socketFd, ackBuf, 1L)
        return when (res) {
            is LinuxNative.SyscallResult.Success -> {
                val value = res.value
                if (value > 0) {
                    val command = ackBuf.readByte(0L)
                    if (command == SHUTDOWN_COMMAND_BYTE) {
                        onShutdown("Parent Command")
                        true
                    } else if (command == PASS_THROUGH_COMMAND_BYTE) {
                        isPassThrough = true
                        // Disable polling on socketFd by setting its fd to -1
                        val pfd2 = PollFdSegment.of(pollFds.asSlice(Layouts.POLLFD_SIZE, Layouts.POLLFD_SIZE))
                        pfd2.setFd(-1)
                        closeControlSocket()
                        false
                    } else {
                        false
                    }
                } else {
                    !isPassThrough
                }
            }
            is LinuxNative.SyscallResult.Error -> true
        }
    }

    private fun closeControlSocket() {
        try {
            socketManager.close(socketFd)
        } catch (_: Exception) {
            return
        }
        onSocketClosed()
    }

    private fun sendSeccompContinue(id: Long, resp: ManagedSegment) {
        resp.fill(0)
        resp.writeLong(RESP_ID_OFF, id)
        resp.writeLong(RESP_VAL_OFF, 0L)
        resp.writeInt(RESP_ERR_OFF, 0)
        resp.writeInt(RESP_FLAGS_OFF, NativeConstants.SECCOMP_USER_NOTIF_FLAG_CONTINUE.toInt())
        while (true) {
            val res = engine.raw.ioctl(listenerFd, IoctlCommand.SECCOMP_IOCTL_NOTIF_SEND, resp.typed<IoctlPayload.SeccompNotifResp>())
            if (res is LinuxNative.SyscallResult.Error<*> && res.errno == NativeConstants.EINTR) {
                continue
            }
            break
        }
    }

    public companion object {
        private const val SHUTDOWN_COMMAND_BYTE: Byte = 0x53.toByte()
        private const val PASS_THROUGH_COMMAND_BYTE: Byte = 0x54.toByte()

        private const val RESP_ID_OFF = 0L
        private const val RESP_VAL_OFF = 8L
        private const val RESP_ERR_OFF = 16L
        private const val RESP_FLAGS_OFF = 20L
    }
}
