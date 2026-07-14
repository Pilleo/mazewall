package io.mazewall.profiler.engine

import io.mazewall.LinuxNative
import io.mazewall.core.Arch
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.Syscall
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.recover
import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Standalone Profiler Daemon Engine.
 */
internal class ProfilerDaemonEngine(
    private val socketPath: String,
    private val transport: ProfilerTransport = RealProfilerTransport,
    private val memoryReader: ProfilerMemoryReader = RealMemoryReader,
) {
    private val publisher: TraceEventPublisher = transport
    private val responder: SeccompResponder = transport
    private val ioOps: NativeIoOperations = transport
    private val socketManager: SocketLifecycleManager = transport

    private val syscallMap = mutableMapOf<Int, String>()
    private val clientSockets = CopyOnWriteArrayList<FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>>()
    private val activeListeners = CopyOnWriteArrayList<FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>>()
    private val stateRef = java.util.concurrent.atomic
        .AtomicReference<ProfilerDaemonState>(ProfilerDaemonState.Uninitialized)

    var state: ProfilerDaemonState
        get() = stateRef.get()
        private set(value) = stateRef.set(value)

    companion object {
        private const val DAEMON_READY_SENTINEL = "MAZEWALL_DAEMON_READY"
        private const val POLL_TIMEOUT_MS = 1000
        private const val POLLFD_FD_OFF = 0L
        private const val POLLFD_EVENTS_OFF = 4L
        private const val ACK_BUF_SIZE = 1L
        private const val PROTOCOL_ACK_BYTE = 0xAC.toByte()
        private const val POLLFD_STRUCT_SIZE = 8L
    }

    init {
        val arch = Arch.current()
        for (s in Syscall.entries) {
            val nr = s.numberFor(arch)
            if (nr >= 0) syscallMap[nr] = s.name
        }
    }

    fun run() {
        val serverFd = socketManager.createUnixServer(socketPath)
        val listeningState = (state as ProfilerDaemonState.Uninitialized).listening(serverFd, socketPath)
        state = listeningState
        System.err.println("[DAEMON] Listening on $socketPath (fd=$serverFd)")

        println(DAEMON_READY_SENTINEL)
        System.out.flush()

        try {
            Arena.ofConfined().use { arena ->
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

    private fun acceptConnections(
        serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        arena: Arena,
    ) {
        val pollFd = arena.allocate(Layouts.POLLFD)
        pollFd.set(ValueLayout.JAVA_INT, POLLFD_FD_OFF, serverFd.value)
        pollFd.set(ValueLayout.JAVA_SHORT, POLLFD_EVENTS_OFF, NativeConstants.POLLIN)

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

    private fun handleNewConnection(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>) {
        try {
            val clientFd = socketManager.accept(serverFd)
            clientSockets.add(clientFd)
            Thread { handleConnection(clientFd) }.apply {
                name = "conn-handler-${clientFd.value}"
                start()
            }
        } catch (e: Exception) {}
    }

    private fun handleConnection(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>) {
        var connection: io.mazewall.ffi.networking.SeccompConnection = io.mazewall.ffi.networking.SeccompConnection.Accepted(socketFd)
        try {
            Arena.ofConfined().use { arena ->
                val pollFd = arena.allocate(Layouts.POLLFD)
                pollFd.set(ValueLayout.JAVA_INT, POLLFD_FD_OFF, socketFd.value)
                pollFd.set(ValueLayout.JAVA_SHORT, POLLFD_EVENTS_OFF, NativeConstants.POLLIN)

                while (!isGlobalShutdown()) {
                    if (connection is io.mazewall.ffi.networking.SeccompConnection.Accepted) {
                        val pollRes = LinuxNative.withTransaction { ioOps.raw.poll(pollFd, 1L, POLL_TIMEOUT_MS) }
                        val count = pollRes.recover { errno, _ ->
                            if (errno != NativeConstants.EINTR) return@use
                            0L
                        }
                        if (count <= 0) continue
                    }

                    when (val current = connection) {
                        is io.mazewall.ffi.networking.SeccompConnection.Accepted -> {
                            val listenerFd = socketManager.recvDescriptor(socketFd)
                            if (listenerFd != null) {
                                activeListeners.add(listenerFd)
                                connection = current.attachFd(listenerFd)
                            } else {
                                return@use
                            }
                        }

                        is io.mazewall.ffi.networking.SeccompConnection.FdAttached -> {
                            val ackBuf = arena.allocate(ACK_BUF_SIZE)
                            ackBuf.set(ValueLayout.JAVA_BYTE, 0L, PROTOCOL_ACK_BYTE)
                            ioOps.write(socketFd, ackBuf, ACK_BUF_SIZE)
                            connection = current.handshakeComplete()
                        }

                        is io.mazewall.ffi.networking.SeccompConnection.Active -> {
                            handleSession(current.socketFd, current.listenerFd)
                            return@use
                        }
                    }
                }
            }
        } catch (e: Exception) {
        } finally {
            clientSockets.remove(socketFd)
            socketManager.close(socketFd)
            if (connection is io.mazewall.ffi.networking.SeccompConnection.FdAttached) {
                val lFd = connection.listenerFd
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
            Arena.ofConfined().use { arena ->
                val pollFds = with(arena) { setupSessionPoll(socketFd, listenerFd) }
                val notif = arena.allocate(Layouts.SECCOMP_NOTIF)
                val resp = arena.allocate(Layouts.SECCOMP_NOTIF_RESP)
                val ackBuf = arena.allocate(1L)
                val socketPollFd = arena.allocate(Layouts.POLLFD)

                while (!isGlobalShutdown()) {
                    val pollRes = LinuxNative.withTransaction { ioOps.raw.poll(pollFds, 2L, POLL_TIMEOUT_MS) }
                    val count = pollRes.recover { errno, _ ->
                        if (errno != NativeConstants.EINTR) return@use
                        0L
                    }
                    if (count <= 0) continue

                    io.mazewall.ffi.memory.nativeScope {
                        val action = with(this) {
                            sessionHandler.handleActiveListener(pollFds, ackBuf, notif, resp, socketPollFd)
                        }
                        if (action !is LoopAction.Continue) triggerGlobalShutdown("session reactor break")
                    }
                    if (isGlobalShutdown()) break
                }
            }
        } finally {
            activeListeners.remove(listenerFd)
            socketManager.close(listenerFd)
        }
    }

    context(arena: Arena)
    private fun setupSessionPoll(
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
    ): MemorySegment {
        val pollFds = arena.allocate(MemoryLayout.sequenceLayout(2, Layouts.POLLFD))
        pollFds.set(ValueLayout.JAVA_INT, 0L, listenerFd.value)
        pollFds.set(ValueLayout.JAVA_SHORT, POLLFD_EVENTS_OFF, NativeConstants.POLLIN)
        pollFds.set(ValueLayout.JAVA_INT, POLLFD_STRUCT_SIZE, socketFd.value)
        pollFds.set(ValueLayout.JAVA_SHORT, POLLFD_STRUCT_SIZE + POLLFD_EVENTS_OFF, NativeConstants.POLLIN)
        return pollFds
    }
}
