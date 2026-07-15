package io.mazewall.enforcer.supervisor

import io.mazewall.onSuccess

import io.mazewall.map

import io.mazewall.isSuccess

import io.mazewall.LinuxNative
import io.mazewall.NativeEngine
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.SocketManager
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.onFailure
import io.mazewall.recover
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.ffi.memory.writeByte
import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout
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
    private val socketPath: String,
    private val engine: NativeEngine = LinuxNative,
    private val socketManager: SocketManager = io.mazewall.core.RealSocketManager
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

        private const val MAX_CONNECTIONS = 200
    }

    fun run() {
        val serverFd = socketManager.createUnixServer(socketPath)
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
            socketManager.close(serverFd)
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
            val count = engine.withTransaction {
                engine.raw.poll(pollFd.segment, 1L, POLL_TIMEOUT_MS)
                    .recover { errno, _ ->
                        if (errno != NativeConstants.EINTR) return@withTransaction -1L
                        0L
                    }
            }
            if (count < 0) return
            if (count == 0L) continue
            handleNewConnection(serverFd)
        }
    }

        internal fun handleNewConnection(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>) {
        try {
            while (true) {
                val resValue = engine.withTransaction {
                    engine.networking.accept(serverFd, java.lang.foreign.MemorySegment.NULL, java.lang.foreign.MemorySegment.NULL)
                        .onFailure { errno, _ ->
                            if (errno != NativeConstants.EAGAIN && errno != NativeConstants.EWOULDBLOCK && errno != NativeConstants.EINTR) {
                                System.err.println("[SUPERVISOR] accept failed: errno=$errno")
                            }
                        }.recover { errno, _ -> if (errno == NativeConstants.EINTR) -1000L else -1L }
                }
                if (resValue == -1000L) continue
                if (resValue >= 0) {
                    val clientFd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(resValue.toInt())

                    if (clientSockets.size >= MAX_CONNECTIONS) {
                        System.err.println("[SUPERVISOR] Rejecting connection: too many clients (${clientSockets.size})")
                        socketManager.close(clientFd)
                        return
                    }

                    clientSockets.add(clientFd)
                    try {
                        connectionExecutor.execute { handleConnection(clientFd) }
                    } catch (e: Exception) {
                        System.err.println("[SUPERVISOR] Failed to execute connection handler: ${e.message}")
                        clientSockets.remove(clientFd)
                        socketManager.close(clientFd)
                    }
                    return
                } else {
                    return
                }
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
            if (clientSockets.remove(socketFd)) {
                socketManager.close(socketFd)
            }
            val lFd = when (connection) {
                is io.mazewall.ffi.networking.SeccompConnection.FdAttached -> connection.listenerFd
                is io.mazewall.ffi.networking.SeccompConnection.Active -> connection.listenerFd
                else -> null
            }
            if (lFd != null && activeListeners.remove(lFd)) {
                socketManager.close(lFd)
            }
        }
    }

    internal fun processConnectionStep(
        arena: Arena,
        connection: io.mazewall.ffi.networking.SeccompConnection,
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        pollFd: PollFdSegment
    ): io.mazewall.ffi.networking.SeccompConnection? {
        if (connection is io.mazewall.ffi.networking.SeccompConnection.Accepted) {
            val count = engine.withTransaction {
                engine.raw.poll(pollFd.segment, 1L, POLL_TIMEOUT_MS)
                    .recover { errno, _ ->
                        if (errno == NativeConstants.EINTR) 0L else -1L
                    }
            }
            if (count < 0) return null
            if (count == 0L) return connection
        }

        return when (val current = connection) {
            is io.mazewall.ffi.networking.SeccompConnection.Accepted -> {
                val listenerFd = socketManager.recvDescriptor(socketFd)
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
                var result: io.mazewall.ffi.networking.SeccompConnection? = null
                while (true) {
                    val resValue = engine.withTransaction {
                        engine.memory.write(socketFd, ackBuf, ACK_BUF_SIZE)
                            .onFailure { errno, _ ->
                                if (errno != NativeConstants.EINTR) {
                                    System.err.println("[SUPERVISOR] Handshake ACK write failed: errno=$errno")
                                }
                            }.recover { errno, _ -> if (errno == NativeConstants.EINTR) -1000L else -1L }
                    }
                    if (resValue == -1000L) continue
                    if (resValue >= 0) {
                        result = current.handshakeComplete()
                        break
                    } else {
                        result = null
                        break
                    }
                }
                result
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
        val sessionHandler = SupervisorSessionHandler(socketFd, listenerFd, engine, socketManager)
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
                    val count = engine.withTransaction {
                        engine.raw.poll(pollFds, 2L, POLL_TIMEOUT_MS)
                            .recover { errno, _ ->
                                if (errno != NativeConstants.EINTR) return@withTransaction -1L
                                0L
                            }
                    }
                    if (count < 0) return@use
                    if (count == 0L) continue

                    val action = sessionHandler.handleActiveListener(pollFds, notif, resp)
                    if (action is LoopAction.Break || action is LoopAction.Shutdown) break
                }
            }
        } finally {
            if (activeListeners.remove(listenerFd)) {
                socketManager.close(listenerFd)
            }
        }
    }
}

internal sealed class LoopAction {
    object Continue : LoopAction()
    object Break : LoopAction()
    object Shutdown : LoopAction()
}
