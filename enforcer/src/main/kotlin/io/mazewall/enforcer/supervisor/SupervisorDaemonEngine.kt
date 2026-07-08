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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    private val connectionExecutor = Executors.newFixedThreadPool(MAX_CONNECTIONS) { r ->
        Thread(r).apply {
            isDaemon = true
            name = "supervisor-conn"
        }
    }

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
        private const val MAX_CONNECTIONS = 200
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
            connectionExecutor.shutdown()
            try {
                if (!connectionExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    connectionExecutor.shutdownNow()
                }
            } catch (e: InterruptedException) {
                connectionExecutor.shutdownNow()
                Thread.currentThread().interrupt()
            }
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

                if (clientSockets.size >= MAX_CONNECTIONS) {
                    System.err.println("[SUPERVISOR] Rejecting connection: too many clients (${clientSockets.size})")
                    closeFd(clientFd)
                    return
                }

                clientSockets.add(clientFd)
                connectionExecutor.execute { handleConnection(clientFd) }
            }
        } catch (ignored: java.io.IOException) {
            // Ignore during shutdown
        } catch (ignored: IllegalStateException) {
            // Ignore during shutdown
        }
    }

    private fun handleConnection(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>) {
        var connection: io.mazewall.ffi.networking.SeccompConnection = io.mazewall.ffi.networking.SeccompConnection.Accepted(socketFd)
        try {
            Arena.ofConfined().use { arena ->
                val pollFd = PollFdSegment(arena.allocate(Layouts.POLLFD))
                pollFd.setFd(socketFd.value)
                pollFd.setEvents(NativeConstants.POLLIN)

                while (!isGlobalShutdown()) {
                    val next = processConnectionStep(arena, connection, socketFd, pollFd) ?: break
                    connection = next
                }
            }
        } finally {
            clientSockets.remove(socketFd)
            closeFd(socketFd)
            if (connection is io.mazewall.ffi.networking.SeccompConnection.FdAttached) {
                val lFd = connection.listenerFd
                activeListeners.remove(lFd)
                closeFd(lFd)
            }
        }
    }

    private fun processConnectionStep(
        arena: Arena,
        connection: io.mazewall.ffi.networking.SeccompConnection,
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        pollFd: PollFdSegment
    ): io.mazewall.ffi.networking.SeccompConnection? {
        if (connection is io.mazewall.ffi.networking.SeccompConnection.Accepted) {
            val pollRes = LinuxNative.withTransaction { LinuxNative.poll(pollFd.segment, 1L, POLL_TIMEOUT_MS) }
            val count = pollRes.recover { errno, _ ->
                if (errno == NativeConstants.EINTR) 0L else -1L
            }
            if (count < 0) return null
            if (count == 0L) return connection
        }

        return when (val current = connection) {
            is io.mazewall.ffi.networking.SeccompConnection.Accepted -> {
                val listenerFd = io.mazewall.ffi.networking.SupervisorSocketUtils.recvDescriptor(socketFd)
                if (listenerFd != null) {
                    System.err.println("[SUPERVISOR] Received listener FD: ${listenerFd.value}")
                    activeListeners.add(listenerFd)
                    current.attachFd(listenerFd)
                } else {
                    null
                }
            }

            is io.mazewall.ffi.networking.SeccompConnection.FdAttached -> {
                System.err.println("[SUPERVISOR] Sending handshake ACK to socket ${socketFd.value}")
                val ackBuf = arena.allocate(ACK_BUF_SIZE)
                ackBuf.writeByte(0L, PROTOCOL_ACK_BYTE)
                LinuxNative.withTransaction { LinuxNative.memory.write(socketFd, ackBuf, ACK_BUF_SIZE) }
                current.handshakeComplete()
            }

            is io.mazewall.ffi.networking.SeccompConnection.Active -> {
                System.err.println("[SUPERVISOR] Starting session reactor for listener ${current.listenerFd.value}")
                handleSession(current.socketFd, current.listenerFd)
                System.err.println("[SUPERVISOR] Session reactor finished. Resetting to Accepted.")
                io.mazewall.ffi.networking.SeccompConnection.Accepted(current.socketFd)
            }
        }
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
            LinuxNative.networking.socket(
                io.mazewall.ffi.networking.SupervisorSocketUtils.AF_UNIX,
                io.mazewall.ffi.networking.SupervisorSocketUtils.SOCK_STREAM,
                0
            )
        }.getFdOrThrow("socket(AF_UNIX)").let { FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(it.value) }

        Arena.ofConfined().use { arena ->
            val sockaddrUn = io.mazewall.ffi.networking.SupervisorSocketUtils.setupSockAddrUn(arena, socketPath)

            LinuxNative.withTransaction {
                LinuxNative.networking.bind(fd, sockaddrUn.segment, io.mazewall.ffi.networking.SupervisorSocketUtils.SOCKADDR_UN_SIZE)
            }.onFailure { _, _ ->
                LinuxNative.fileSystem.close(fd)
            }.getOrThrow("bind(AF_UNIX)")
        }

        LinuxNative.withTransaction {
            LinuxNative.networking.listen(fd, io.mazewall.ffi.networking.SupervisorSocketUtils.BACKLOG_SIZE)
        }.onFailure { _, _ ->
            LinuxNative.fileSystem.close(fd)
        }.getOrThrow("listen")

        return fd
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
