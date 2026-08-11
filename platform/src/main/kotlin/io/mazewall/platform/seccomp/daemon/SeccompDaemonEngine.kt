package io.mazewall.platform.seccomp.daemon

import io.mazewall.LinuxNative
import io.mazewall.NativeEngine
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.RealSocketManager
import io.mazewall.core.SocketManager
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.networking.SeccompConnection
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.ffi.memory.writeByte
import io.mazewall.recover

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

public sealed interface SeccompDaemonState {
    public object Uninitialized : SeccompDaemonState {
        public fun listening(
            serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
            socketPath: String
        ): Listening = Listening(serverFd, socketPath)
    }

    public data class Listening(
        val serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        val socketPath: String
    ) : SeccompDaemonState {
        public fun active(): Active = Active(serverFd, socketPath)
    }

    public data class Active(
        val serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        val socketPath: String
    ) : SeccompDaemonState

    public object ShuttingDown : SeccompDaemonState
    public object Terminated : SeccompDaemonState
}

/**
 * Unified daemon engine for handling seccomp user notification UNIX server, client connections,
 * handshake protocol, and session reactors.
 */
public class SeccompDaemonEngine(
    private val socketPath: String,
    private val readySentinel: String = "MAZEWALL_DAEMON_READY",
    private val notifHandlerFactory: (
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>
    ) -> SeccompNotifHandler,
    private val handshakeAckByte: Byte = PROTOCOL_ACK_BYTE,
    private val maxConnections: Int = 200,

    private val engine: NativeEngine = LinuxNative,
    private val socketManager: SocketManager = RealSocketManager,
) {
    /**
     * Connection tasks spend most of their lifetime blocked in poll(2). A fixed-size pool turns
     * its size into an accidental session limit, so idle workers are reused without capping the
     * number of simultaneously active sessions.
     */
    private val connectionExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable).apply {
            isDaemon = true
            name = "seccomp-conn"
        }
    }

    @JvmField
    public val clientSockets = CopyOnWriteArrayList<FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>>()

    private val activeListeners = CopyOnWriteArrayList<FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>>()
    private val stateRef = AtomicReference<SeccompDaemonState>(SeccompDaemonState.Uninitialized)
    public var state: SeccompDaemonState
        get() = stateRef.get()
        private set(value) = stateRef.set(value)

    public companion object {
        private const val POLL_TIMEOUT_MS = 1000
        private const val POLLFD_STRUCT_SIZE = 8L
        private const val ACK_BUF_SIZE = 1L
        private const val PROTOCOL_ACK_BYTE = 0xAC.toByte()
    }

    public fun run() {
        val serverFd = socketManager.createUnixServer(socketPath)
        val listeningState = (state as SeccompDaemonState.Uninitialized).listening(serverFd, socketPath)
        state = listeningState
        System.err.println("[SECCOMP-DAEMON] Listening on $socketPath (fd=$serverFd)")

        println(readySentinel)
        System.out.flush()

        try {
            state = listeningState.active()
            NativeArena.ofConfined().use { arena ->
                val pollFd = PollFdSegment.of(arena.allocate(Layouts.POLLFD))
                pollFd.setFd(serverFd.value)
                pollFd.setEvents(NativeConstants.POLLIN)

                while (!isGlobalShutdown()) {
                    val pollRes = engine.raw.poll(pollFd.managed, 1L, POLL_TIMEOUT_MS)
                    val count = pollRes.recover { errno, _ ->
                        if (errno != NativeConstants.EINTR) return@use
                        0L
                    }

                    if (count > 0 && (pollFd.getRevents().toInt() and NativeConstants.POLLIN.toInt()) != 0) {
                        handleNewConnection(serverFd)
                    }
                }
            }
        } finally {
            state = SeccompDaemonState.Terminated
            socketManager.close(serverFd)

            val clientsToClose = ArrayList(clientSockets)
            clientSockets.clear()
            for (clientFd in clientsToClose) {
                try { socketManager.close(clientFd) } catch (_: Exception) {}
            }

            val listenersToClose = ArrayList(activeListeners)
            activeListeners.clear()
            for (listenerFd in listenersToClose) {
                try { socketManager.close(listenerFd) } catch (_: Exception) {}
            }
            connectionExecutor.shutdownNow()
        }
    }

    public fun triggerGlobalShutdown(source: String = "unknown") {
        while (true) {
            val curr = stateRef.get()
            if (curr is SeccompDaemonState.ShuttingDown || curr is SeccompDaemonState.Terminated) return
            if (stateRef.compareAndSet(curr, SeccompDaemonState.ShuttingDown)) {
                System.err.println("[SECCOMP-DAEMON] Initiating graceful shutdown. Source: $source.")
                break
            }
        }
    }

    public fun isGlobalShutdown(): Boolean {
        val curr = state
        return curr is SeccompDaemonState.ShuttingDown || curr is SeccompDaemonState.Terminated
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    public fun handleNewConnection(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>) {
        var clientFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>? = null
        try {
            while (true) {
                val res = engine.networking.accept4(
                    serverFd,
                    ManagedSegment.NULL,
                    ManagedSegment.NULL,
                    NativeConstants.SOCK_CLOEXEC
                )
                val clientFdVal = res.recover { errno, _ ->
                    if (errno == NativeConstants.EINTR) return@recover -1L
                    -2L
                }

                if (clientFdVal == -1L) {
                    continue
                }

                clientFd = if (clientFdVal > 0L) {
                    FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(clientFdVal.toInt())
                } else {
                    try {
                        socketManager.accept(serverFd)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    } catch (e: java.nio.channels.ClosedByInterruptException) {
                        Thread.currentThread().interrupt()
                        return
                    } catch (_: Exception) {
                        return
                    }
                }

                clientSockets.add(clientFd)
                
                // Run each persistent connection independently of a fixed worker limit.
                connectionExecutor.submit {
                    handleConnection(clientFd)
                }
                return
            }
        } catch (e: InterruptedException) {
            if (clientFd != null) {
                clientSockets.remove(clientFd)
                try { socketManager.close(clientFd) } catch (_: Exception) {}
            }
            Thread.currentThread().interrupt()
        } catch (e: java.nio.channels.ClosedByInterruptException) {
            if (clientFd != null) {
                clientSockets.remove(clientFd)
                try { socketManager.close(clientFd) } catch (_: Exception) {}
            }
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            if (clientFd != null) {
                clientSockets.remove(clientFd)
                try { socketManager.close(clientFd) } catch (_: Exception) {}
            }
            if (t is Error) throw t
        }
    }

    @Suppress("TooGenericExceptionCaught")
    public fun handleConnection(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>) {
        var connection: SeccompConnection = SeccompConnection.Accepted(socketFd).apply {
            this.socketManager = this@SeccompDaemonEngine.socketManager
        }
        var interrupted = false

        try {
            NativeArena.ofConfined().use { arena ->
                val pollFd = PollFdSegment.of(arena.allocate(Layouts.POLLFD))
                pollFd.setFd(socketFd.value)
                pollFd.setEvents(NativeConstants.POLLIN)
                val pollFdManaged = pollFd.managed

                while (!isGlobalShutdown()) {
                    val next = processConnectionStep(arena, connection, socketFd, pollFdManaged) ?: break
                    connection = next
                }
            }
        } catch (e: InterruptedException) {
            System.err.println("[SECCOMP-DAEMON] Connection handler interrupted: ${e.message}")
            interrupted = true
        } catch (e: java.nio.channels.ClosedByInterruptException) {
            System.err.println("[SECCOMP-DAEMON] Connection handler channel closed by interrupt: ${e.message}")
            interrupted = true
        } catch (e: Exception) {
            System.err.println("[SECCOMP-DAEMON-WARN] Connection handler terminated with exception: ${e.message}")
        } finally {
            clientSockets.remove(socketFd)
            connection.listenerFd?.let { activeListeners.remove(it) }
            connection.close()
            if (interrupted) {
                Thread.currentThread().interrupt()
            }
        }
    }

    public fun processConnectionStep(
        arena: NativeArena,
        connection: SeccompConnection,
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        pollFdManaged: ManagedSegment
    ): SeccompConnection? {
        if (connection is SeccompConnection.Accepted) {
            val pollRes = engine.raw.poll(pollFdManaged, 1L, POLL_TIMEOUT_MS)
            val count = pollRes.recover { errno, _ ->
                if (errno == NativeConstants.EINTR) 0L else -1L
            }
            if (count < 0) return null
            if (count == 0L) return connection
        }

        return when (val current = connection) {
            is SeccompConnection.Accepted -> {
                val listenerFd = socketManager.recvDescriptor(socketFd)
                if (listenerFd != null) {
                    System.err.println("[SECCOMP-DAEMON] Received listener FD: ${listenerFd.value}")
                    activeListeners.add(listenerFd)
                    current.attachFd(listenerFd).also { it.socketManager = socketManager }
                } else {
                    null
                }
            }

            is SeccompConnection.FdAttached -> {
                System.err.println("[SECCOMP-DAEMON] Sending handshake ACK to socket ${socketFd.value}")
                val ackBuf = arena.allocate(ACK_BUF_SIZE)
                ackBuf.writeByte(0L, handshakeAckByte)
                var result: SeccompConnection? = null
                while (true) {
                    val res = engine.memory.write(socketFd, ackBuf, ACK_BUF_SIZE)
                    if (res is LinuxNative.SyscallResult.Success) {
                        result = current.handshakeComplete().also { it.socketManager = socketManager }
                        break
                    } else {
                        val errno = (res as LinuxNative.SyscallResult.Error).errno
                        if (errno == NativeConstants.EINTR) continue
                        result = null
                        break
                    }
                }
                result
            }

            is SeccompConnection.Active -> {
                System.err.println("[SECCOMP-DAEMON] Starting session reactor for listener ${current.listenerFd.value}")
                handleSession(current.socketFd, current.listenerFd)
                System.err.println("[SECCOMP-DAEMON] Session reactor finished. Closing connection.")
                null
            }
        }
    }

    private fun handleSession(
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>
    ) {
        val notifHandler = notifHandlerFactory(socketFd, listenerFd)
        SeccompSessionHandler(
            socketFd = socketFd,
            listenerFd = listenerFd,
            notifHandler = notifHandler,
            onShutdown = this::triggerGlobalShutdown,
            engine = engine,
            socketManager = socketManager,
        ).use { sessionHandler ->
            try {
                NativeArena.ofConfined().use { sessionArena ->
                    val pollFds = sessionArena.allocate(Layouts.POLLFD, 2)
                    val pfd1 = PollFdSegment.of(pollFds.asSlice(0L, Layouts.POLLFD_SIZE))
                    pfd1.setFd(listenerFd.value)
                    pfd1.setEvents(NativeConstants.POLLIN)

                    val pfd2 = PollFdSegment.of(pollFds.asSlice(POLLFD_STRUCT_SIZE, Layouts.POLLFD_SIZE))
                    pfd2.setFd(socketFd.value)
                    pfd2.setEvents(NativeConstants.POLLIN)

                    while (!isGlobalShutdown()) {
                        val pollRes = engine.raw.poll(pollFds, 2L, POLL_TIMEOUT_MS)
                        val count = pollRes.recover { errno, _ ->
                            if (errno != NativeConstants.EINTR) return@use
                            0L
                        }
                        if (count <= 0) continue

                        var shouldBreak = false
                        NativeArena.ofConfined().use { iterationArena ->
                            val action = with(iterationArena) {
                                sessionHandler.handleActiveListener(pollFds)
                            }
                            when (action) {
                                is LoopAction.Shutdown -> {
                                    triggerGlobalShutdown("session reactor shutdown action")
                                    shouldBreak = true
                                }
                                is LoopAction.Break -> {
                                    shouldBreak = true
                                }
                                is LoopAction.Continue -> {}
                            }
                        }
                        if (shouldBreak) break
                        if (isGlobalShutdown()) break
                    }
                }
            } finally {
                // connection.close() in handleConnection's finally block will handle listenerFd closure.
            }
        }
    }
}
