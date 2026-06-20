package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.getFdOrThrow
import io.mazewall.onSuccess
import io.mazewall.onFailure
import io.mazewall.recover
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.ffi.memory.SockaddrUnSegment
import io.mazewall.ffi.memory.IovecSegment
import io.mazewall.ffi.memory.MsghdrSegment
import io.mazewall.ffi.memory.CmsghdrSegment
import io.mazewall.ffi.memory.writeByte
import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

internal sealed interface SupervisorDaemonState {
    object Uninitialized : SupervisorDaemonState {
        fun listening(
            serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
            socketPath: String
        ) = Listening(serverFd, socketPath)
    }

    data class Listening(
        val serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        val socketPath: String
    ) : SupervisorDaemonState {
        fun active() = Active(serverFd, socketPath)
    }

    data class Active(
        val serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        val socketPath: String
    ) : SupervisorDaemonState

    object ShuttingDown : SupervisorDaemonState
    object Terminated : SupervisorDaemonState
}

internal class SupervisorDaemonEngine(
    private val socketPath: String
) {
    private val clientSockets = CopyOnWriteArrayList<FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>>()
    private val activeListeners = CopyOnWriteArrayList<FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>>()
    private val stateRef = AtomicReference<SupervisorDaemonState>(SupervisorDaemonState.Uninitialized)

    var state: SupervisorDaemonState
        get() = stateRef.get()
        private set(value) = stateRef.set(value)

    companion object {
        private const val POLL_TIMEOUT_MS = 1000
        private const val POLLFD_STRUCT_SIZE = 8L
        private const val ACK_BUF_SIZE = 1L
        private const val PROTOCOL_ACK_BYTE = 0xAC.toByte()

        private const val CMSG_RIGHTS_LEN = 20L
        private const val MSG_CONTROL_BUF_SIZE = 24L
        private const val SOL_SOCKET = 1
        private const val SCM_RIGHTS = 1
        private const val EINTR = 4
        private const val BACKLOG_SIZE = 128
        private const val SOCKADDR_UN_SIZE = 110
    }

    fun run() {
        val serverFd = createServer(socketPath)
        val listeningState = (state as SupervisorDaemonState.Uninitialized).listening(serverFd, socketPath)
        state = listeningState
        System.err.println("[SUPERVISOR] Listening on $socketPath (fd=$serverFd)")

        println(SupervisorDaemon.DAEMON_READY_SENTINEL)
        System.out.flush()

        try {
            Arena.ofConfined().use { arena ->
                state = listeningState.active()
                acceptConnections(serverFd, arena)
            }
        } finally {
            state = SupervisorDaemonState.Terminated
            closeFd(serverFd)
        }
    }

    fun triggerGlobalShutdown(source: String = "unknown") {
        while (true) {
            val curr = stateRef.get()
            if (curr is SupervisorDaemonState.ShuttingDown || curr is SupervisorDaemonState.Terminated) return
            if (stateRef.compareAndSet(curr, SupervisorDaemonState.ShuttingDown)) {
                System.err.println("[SUPERVISOR] Initiating graceful shutdown. Source: $source.")
                break
            }
        }
    }

    private fun isGlobalShutdown(): Boolean {
        val curr = state
        return curr is SupervisorDaemonState.ShuttingDown || curr is SupervisorDaemonState.Terminated
    }

    private fun acceptConnections(
        serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        arena: Arena
    ) {
        val pollFd = PollFdSegment(arena.allocate(Layouts.POLLFD))
        pollFd.setFd(serverFd.value)
        pollFd.setEvents(NativeConstants.POLLIN)

        while (!isGlobalShutdown()) {
            val pollRes = LinuxNative.withTransaction { LinuxNative.poll(pollFd.segment, 1L, POLL_TIMEOUT_MS) }
            val count = pollRes.recover { errno, _ ->
                if (errno != NativeConstants.EINTR) return
                0L
            }
            if (count <= 0) continue
            handleNewConnection(serverFd)
        }
    }

    private fun handleNewConnection(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>) {
        try {
            val res = LinuxNative.withTransaction { LinuxNative.networking.accept(serverFd, MemorySegment.NULL, MemorySegment.NULL) }
            if (res is LinuxNative.SyscallResult.Success) {
                val clientFd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(res.value.toInt())
                clientSockets.add(clientFd)
                Thread { handleConnection(clientFd) }.apply {
                    name = "supervisor-conn-handler-${clientFd.value}"
                    start()
                }
            }
        } catch (ignored: java.io.IOException) {
            // Ignore during shutdown
        } catch (ignored: IllegalStateException) {
            // Ignore during shutdown
        }
    }

    private fun handleConnection(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>) {
        try {
            Arena.ofConfined().use { arena ->
                val pollFd = PollFdSegment(arena.allocate(Layouts.POLLFD))
                pollFd.setFd(socketFd.value)
                pollFd.setEvents(NativeConstants.POLLIN)
                connectionLoop(socketFd, pollFd, arena)
            }
        } finally {
            clientSockets.remove(socketFd)
            closeFd(socketFd)
        }
    }

    private fun connectionLoop(
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        pollFd: PollFdSegment,
        arena: Arena
    ) {
        var isFdAttached = false
        var listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>? = null
        try {
            while (!isGlobalShutdown()) {
                if (!isFdAttached) {
                    val received = pollAndReceiveDescriptor(socketFd, pollFd, arena)
                    if (received != null) {
                        listenerFd = received
                        isFdAttached = true
                    } else {
                        break
                    }
                } else {
                    val lFd = listenerFd ?: break
                    System.err.println("[SUPERVISOR] Starting session reactor for listener ${lFd.value}")
                    handleSession(socketFd, lFd)
                    isFdAttached = false
                    listenerFd = null
                }
            }
        } finally {
            listenerFd?.let {
                activeListeners.remove(it)
                closeFd(it)
            }
        }
    }

    private fun pollAndReceiveDescriptor(
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        pollFd: PollFdSegment,
        arena: Arena
    ): FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>? {
        val pollRes = LinuxNative.withTransaction { LinuxNative.poll(pollFd.segment, 1L, POLL_TIMEOUT_MS) }
        val count = pollRes.recover { errno, _ ->
            if (errno == NativeConstants.EINTR) 0L else -1L
        }
        if (count < 0) return null

        val received = recvDescriptor(socketFd) ?: return null
        System.err.println("[SUPERVISOR] Received listener FD: ${received.value}")
        activeListeners.add(received)

        // Send ACK byte to notify receipt of listener FD
        val ackBuf = arena.allocate(ACK_BUF_SIZE)
        ackBuf.writeByte(0L, PROTOCOL_ACK_BYTE)
        LinuxNative.withTransaction { LinuxNative.memory.write(socketFd, ackBuf, ACK_BUF_SIZE) }
        return received
    }

    private fun handleSession(
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>
    ) {
        val sessionHandler = SupervisorSessionHandler(socketFd, listenerFd)
        try {
            Arena.ofConfined().use { arena ->
                val pollFds = arena.allocate(MemoryLayout.sequenceLayout(2, Layouts.POLLFD))
                val pfd1 = PollFdSegment(pollFds.asSlice(0L, Layouts.POLLFD.byteSize()))
                pfd1.setFd(listenerFd.value)
                pfd1.setEvents(NativeConstants.POLLIN)

                val pfd2 = PollFdSegment(pollFds.asSlice(POLLFD_STRUCT_SIZE, Layouts.POLLFD.byteSize()))
                pfd2.setFd(socketFd.value)
                pfd2.setEvents(NativeConstants.POLLIN)

                val notif = arena.allocate(Layouts.SECCOMP_NOTIF)
                val resp = arena.allocate(Layouts.SECCOMP_NOTIF_RESP)

                while (!isGlobalShutdown()) {
                    val pollRes = LinuxNative.withTransaction { LinuxNative.poll(pollFds, 2L, POLL_TIMEOUT_MS) }
                    val count = pollRes.recover { errno, _ ->
                        if (errno != NativeConstants.EINTR) return@use
                        0L
                    }
                    if (count <= 0) continue

                    val action = sessionHandler.handleActiveListener(pollFds, notif, resp)
                    if (action is LoopAction.Break || action is LoopAction.Shutdown) break
                }
            }
        } finally {
            activeListeners.remove(listenerFd)
            closeFd(listenerFd)
        }
    }

    private fun createServer(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> {
        val fd = LinuxNative.withTransaction {
            LinuxNative.networking.socket(1, 1, 0) // AF_UNIX = 1, SOCK_STREAM = 1
        }.getFdOrThrow("socket(AF_UNIX)").let { FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(it.value) }

        Arena.ofConfined().use { arena ->
            val sockaddrUn = SockaddrUnSegment(arena.allocate(Layouts.SOCKADDR_UN))
            sockaddrUn.setSunFamily(1.toShort()) // AF_UNIX = 1
            val pathBytes = socketPath.toByteArray(StandardCharsets.UTF_8)
            val pathSeg = sockaddrUn.getSunPath()
            MemorySegment.copy(pathBytes, 0, pathSeg, ValueLayout.JAVA_BYTE, 0L, pathBytes.size)

            LinuxNative.withTransaction {
                LinuxNative.networking.bind(fd, sockaddrUn.segment, SOCKADDR_UN_SIZE)
            }.onFailure { _, _ ->
                LinuxNative.fileSystem.close(fd)
            }.getOrThrow("bind(AF_UNIX)")
        }

        LinuxNative.withTransaction {
            LinuxNative.networking.listen(fd, BACKLOG_SIZE)
        }.onFailure { _, _ ->
            LinuxNative.fileSystem.close(fd)
        }.getOrThrow("listen")

        return fd
    }

    private fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>? {
        return Arena.ofConfined().use { arena ->
            val dummyByte = arena.allocate(ValueLayout.JAVA_BYTE)
            val controlBuf = arena.allocate(MSG_CONTROL_BUF_SIZE)
            controlBuf.fill(0)

            val iov = IovecSegment(arena.allocate(Layouts.IOVEC))
            iov.setIovBase(dummyByte)
            iov.setIovLen(1L)

            val msg = MsghdrSegment(arena.allocate(Layouts.MSGHDR))
            msg.setMsgIov(iov.segment)
            msg.setMsgIovlen(1L)
            msg.setMsgControl(controlBuf)
            msg.setMsgControllen(MSG_CONTROL_BUF_SIZE)

            val cmsg = CmsghdrSegment(controlBuf)

            while (true) {
                val res = LinuxNative.withTransaction { LinuxNative.networking.recvmsg(socketFd, msg.segment, 0) }
                if (res is LinuxNative.SyscallResult.Success) {
                    val value = res.value
                    if (value == 0L) return@use null

                    val cmsgLen = cmsg.getCmsgLen()
                    val cmsgLevel = cmsg.getCmsgLevel()
                    val cmsgType = cmsg.getCmsgType()
                    if (cmsgLen >= CMSG_RIGHTS_LEN && cmsgLevel == SOL_SOCKET && cmsgType == SCM_RIGHTS) {
                        return@use FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(cmsg.getDataFd())
                    }
                } else {
                    val errno = (res as LinuxNative.SyscallResult.Error).errno
                    if (errno == EINTR) continue
                    return@use null
                }
            }
            null
        }
    }

    private fun closeFd(fd: FileDescriptor<*, FdState.Open>) {
        LinuxNative.fileSystem.close(fd)
    }
}

internal sealed class LoopAction {
    object Continue : LoopAction()
    object Break : LoopAction()
    object Shutdown : LoopAction()
}
