package io.mazewall.profiler.engine

import io.mazewall.LinuxNative
import io.mazewall.NativeEngine
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.SocketManager
import io.mazewall.core.LoopAction
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.*
import io.mazewall.recover
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class ProfilerDaemonEngine(
    private val socketPath: String,
    private val syscallMap: Map<Int, String> = emptyMap(),
    private val publisher: TraceEventPublisher = RealProfilerTransport,
    private val responder: SeccompResponder = RealProfilerTransport,
    private val ioOps: NativeIoOperations = RealProfilerTransport,
    private val memoryReader: ProfilerMemoryReader = RealMemoryReader,
    private val socketManager: SocketManager = io.mazewall.core.RealSocketManager,
) {
    private val clientSockets = CopyOnWriteArrayList<FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>>()
    private val activeListeners = CopyOnWriteArrayList<FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>>()
    private val stateRef = AtomicReference<ProfilerDaemonState>(ProfilerDaemonState.Uninitialized)

    var state: ProfilerDaemonState
        get() = stateRef.get()
        private set(value) = stateRef.set(value)

    companion object {
        private const val POLL_TIMEOUT_MS = 1000
        private const val ACK_BUF_SIZE = 1L
        private const val PROTOCOL_ACK_BYTE = 0xAC.toByte()

        private const val POLLFD_STRUCT_SIZE = 8L
        private val POLLFD_FD_OFF = Layouts.POLLFD_FD_OFFSET
        private val POLLFD_EVENTS_OFF = Layouts.POLLFD_EVENTS_OFFSET

        private const val DAEMON_READY_SENTINEL = "MAZEWALL_PROFILER_DAEMON_READY"
    }

    fun run() {
        val serverFd = socketManager.createUnixServer(socketPath)
        val listeningState = (state as ProfilerDaemonState.Uninitialized).listening(serverFd, socketPath)
        state = listeningState
        System.err.println("[DAEMON] Listening on $socketPath (fd=$serverFd)")

        // Signal readiness to parent process via stdout sentinel
        println(DAEMON_READY_SENTINEL)
        System.out.flush()

        try {
            NativeArena.ofConfined().use { arena ->
                state = listeningState.active()
                acceptConnections(serverFd, arena)
            }
        } finally {
            state = ProfilerDaemonState.Terminated
            socketManager.close(serverFd)
        }
    }

    fun triggerGlobalShutdown(source: String = "unknown") {
        while (true) {
            val curr = stateRef.get()
            if (curr is ProfilerDaemonState.ShuttingDown || curr is ProfilerDaemonState.Terminated) return
            if (stateRef.compareAndSet(curr, ProfilerDaemonState.ShuttingDown)) {
                System.err.println("[DAEMON] Initiating graceful shutdown. Source: $source. Releasing tracee threads...")
                break
            }
        }
    }

    private fun isGlobalShutdown(): Boolean {
        val curr = state
        return curr is ProfilerDaemonState.ShuttingDown || curr is ProfilerDaemonState.Terminated
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private fun acceptConnections(
        serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        arena: NativeArena,
    ) {
        val pollFd = arena.allocate(Layouts.POLLFD)
        pollFd.writeInt(POLLFD_FD_OFF, serverFd.value)
        pollFd.writeShort(POLLFD_EVENTS_OFF, NativeConstants.POLLIN)

        while (!isGlobalShutdown()) {
            val pollRes = LinuxNative.withTransaction { ioOps.raw.poll(pollFd, 1L, POLL_TIMEOUT_MS) }
            val count = pollRes.recover { errno, _ ->
                if (errno != NativeConstants.EINTR) return
                0L
            }
            if (count <= 0) continue
            handleNewConnection(serverFd)
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun handleNewConnection(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>) {
        try {
            val clientFd = socketManager.accept(serverFd)
            clientSockets.add(clientFd)
            Thread { handleConnection(clientFd) }.apply {
                name = "conn-handler-${clientFd.value}"
                start()
            }
        } catch (e: Exception) {
            System.err.println("WARN: handleNewConnection failed: ${e.message}")
        }
    }

    @Suppress("NestedBlockDepth", "LoopWithTooManyJumpStatements", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
    private fun handleConnection(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>) {
        var connection: io.mazewall.ffi.networking.SeccompConnection = io.mazewall.ffi.networking.SeccompConnection.Accepted(socketFd)
        try {
            NativeArena.ofConfined().use { arena ->
                val pollFd = arena.allocate(Layouts.POLLFD)
                pollFd.writeInt(POLLFD_FD_OFF, socketFd.value)
                pollFd.writeShort(POLLFD_EVENTS_OFF, NativeConstants.POLLIN)

                while (!isGlobalShutdown()) {
                    // Only poll if we are waiting for a NEW listener FD (Accepted state)
                    if (connection is io.mazewall.ffi.networking.SeccompConnection.Accepted) {
                        val pollRes = LinuxNative.withTransaction { ioOps.raw.poll(pollFd, 1L, POLL_TIMEOUT_MS) }
                        val count = pollRes.recover { errno, _ ->
                            if (errno != NativeConstants.EINTR) return@use // Break from loop
                            0L
                        }
                        if (count <= 0) continue
                    }

                    when (val current = connection) {
                        is io.mazewall.ffi.networking.SeccompConnection.Accepted -> {
                            val listenerFd = socketManager.recvDescriptor(socketFd)
                            if (listenerFd != null) {
                                System.err.println("[DAEMON] Received listener FD: ${listenerFd.value}")
                                activeListeners.add(listenerFd)
                                connection = current.attachFd(listenerFd)
                                // Immediately loop to send ACK (don't poll)
                            } else {
                                return@use
                            }
                        }

                        is io.mazewall.ffi.networking.SeccompConnection.FdAttached -> {
                            System.err.println("[DAEMON] Sending handshake ACK to socket ${socketFd.value}")
                            val ackBuf = arena.allocate(ACK_BUF_SIZE)
                            ackBuf.writeByte(0L, PROTOCOL_ACK_BYTE)
                            var success = false
                            while (true) {
                                val res = ioOps.write(socketFd, ackBuf, ACK_BUF_SIZE)
                                if (res is io.mazewall.LinuxNative.SyscallResult.Success) {
                                    success = true
                                    break
                                } else {
                                    val errno = (res as io.mazewall.LinuxNative.SyscallResult.Error).errno
                                    if (errno == NativeConstants.EINTR) continue
                                    break
                                }
                            }
                            if (success) {
                                connection = current.handshakeComplete()
                            } else {
                                return@use
                            }
                            // Immediately loop to start session reactor (don't poll)
                        }

                        is io.mazewall.ffi.networking.SeccompConnection.Active -> {
                            System.err.println("[DAEMON] Starting session reactor for listener ${current.listenerFd.value}")
                            handleSession(current.socketFd, current.listenerFd)
                            // After session finishes (e.g. shutdown command received), terminate
                            // the connection entirely. The trace listener expects EOF on the
                            // socket to know all events are drained.
                            System.err.println("[DAEMON] Session reactor finished. Closing connection.")
                            return@use
                        }
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[DAEMON-WARN] Connection handler terminated with exception: ${e.message}")
        } finally {
            clientSockets.remove(socketFd)
            socketManager.close(socketFd)
            if (connection is io.mazewall.ffi.networking.SeccompConnection.FdAttached) {
                val lFd = connection.listenerFd
                activeListeners.remove(lFd)
                socketManager.close(lFd)
            } else if (connection is io.mazewall.ffi.networking.SeccompConnection.Active) {
                val lFd = (connection as io.mazewall.ffi.networking.SeccompConnection.Active).listenerFd
                activeListeners.remove(lFd)
                socketManager.close(lFd)
            }
        }
    }

    private fun handleSession(
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
    ) {
        val sessionHandler = ProfilerSessionHandler(
            socketFd = socketFd,
            listenerFd = listenerFd,
            publisher = publisher,
            responder = responder,
            ioOps = ioOps,
            memoryReader = memoryReader,
            syscallMap = syscallMap,
            onShutdown = this::triggerGlobalShutdown,
        )
        try {
            NativeArena.ofConfined().use { arena ->
                val pollFds = setupSessionPoll(arena, socketFd, listenerFd)
                val notif = arena.allocate(Layouts.SECCOMP_NOTIF)
                val resp = arena.allocate(Layouts.SECCOMP_NOTIF_RESP)
                val ackBuf = arena.allocate(1L)
                val socketPollFd = arena.allocate(Layouts.POLLFD)

                while (!isGlobalShutdown()) {
                    val pollRes = LinuxNative.withTransaction { ioOps.raw.poll(pollFds, 2L, POLL_TIMEOUT_MS) }
                    val count = pollRes.recover { errno, _ ->
                        if (errno != NativeConstants.EINTR) return@use // Break from loop
                        0L
                    }
                    if (count <= 0) continue

                    val action = nativeScope {
                        sessionHandler.handleActiveListener(pollFds, ackBuf, notif, resp, socketPollFd)
                    }
                    if (action !is LoopAction.Continue) break
                    if (isGlobalShutdown()) break
                }
            }
        } finally {
            activeListeners.remove(listenerFd)
            socketManager.close(listenerFd)
        }
    }

    private fun setupSessionPoll(
        arena: NativeArena,
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
    ): ManagedSegment {
        val pollFds = arena.allocate(Layouts.POLLFD, 2)
        // [0]: Seccomp listener FD
        pollFds.writeInt(0L, listenerFd.value)
        pollFds.writeShort(POLLFD_EVENTS_OFF, NativeConstants.POLLIN)
        // [1]: UNIX socket FD (for parent shutdown/ACK)
        pollFds.writeInt(POLLFD_STRUCT_SIZE, socketFd.value)
        pollFds.writeShort(POLLFD_STRUCT_SIZE + POLLFD_EVENTS_OFF, NativeConstants.POLLIN)
        return pollFds
    }
}
