package io.mazewall.platform.seccomp.daemon

import io.mazewall.LinuxNative
import io.mazewall.NativeEngine
import io.mazewall.RawSyscallOperations
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.RealSocketManager
import io.mazewall.core.SocketManager
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.networking.SeccompConnection
import io.mazewall.ffi.networking.SeccompConnectionEffect
import io.mazewall.ffi.networking.SeccompConnectionEvent
import io.mazewall.ffi.networking.SeccompConnectionMachine
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.ffi.memory.writeByte
import io.mazewall.platform.daemon.UnixListenDaemonEffect
import io.mazewall.platform.daemon.UnixListenDaemonEvent
import io.mazewall.platform.daemon.UnixListenDaemonMachine
import io.mazewall.platform.daemon.UnixListenDaemonState
import io.mazewall.platform.daemon.UnixListenDaemonTransition
import io.mazewall.recover

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
    private val raw: RawSyscallOperations = engine.raw,
    private val handshakeWriter: (
        FileDescriptor<*, FdState.Open>,
        ManagedSegment,
        Long,
    ) -> LinuxNative.SyscallResult<Long, *> = engine.memory::write,
    private val connectionAcceptor: ((
        FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
    ) -> FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>)? = null,
) {
    private val connectionExecutor: ExecutorService = Executors.newFixedThreadPool(maxConnections) { r ->
        Thread(r).apply {
            isDaemon = true
            name = "seccomp-conn"
        }
    }

    @JvmField
    public val clientSockets = CopyOnWriteArrayList<FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>>()

    private val activeListeners = CopyOnWriteArrayList<FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>>()
    private val stateRef = AtomicReference<UnixListenDaemonState>(UnixListenDaemonState.Uninitialized)
    public var state: UnixListenDaemonState
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
        applyEvent(UnixListenDaemonEvent.Bound(serverFd, socketPath))
        applyEvent(UnixListenDaemonEvent.ReadyAnnounced)

        try {
            NativeArena.ofConfined().use { arena ->
                val pollFd = PollFdSegment.of(arena.allocate(Layouts.POLLFD))
                pollFd.setFd(serverFd.value)
                pollFd.setEvents(NativeConstants.POLLIN)

                while (!isGlobalShutdown()) {
                    val pollRes = raw.poll(pollFd.managed, 1L, POLL_TIMEOUT_MS)
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
            applyEvent(UnixListenDaemonEvent.AcceptLoopFinished(serverFd))
        }
    }

    public fun triggerGlobalShutdown(source: String = "unknown") {
        applyEvent(UnixListenDaemonEvent.ShutdownRequested(source))
    }

    internal fun applyEvent(event: UnixListenDaemonEvent): UnixListenDaemonTransition {
        return UnixListenDaemonMachine.apply(stateRef, event, ::executeEffects)
    }

    private fun executeEffects(effects: List<UnixListenDaemonEffect>) {
        for (effect in effects) {
            when (effect) {
                is UnixListenDaemonEffect.LogListening ->
                    System.err.println("[SECCOMP-DAEMON] Listening on ${effect.socketPath} (fd=${effect.serverFd})")
                is UnixListenDaemonEffect.PublishReady -> {
                    println(readySentinel)
                    System.out.flush()
                }
                is UnixListenDaemonEffect.LogShutdown ->
                    System.err.println("[SECCOMP-DAEMON] Initiating graceful shutdown. Source: ${effect.source}.")
                is UnixListenDaemonEffect.CloseServer ->
                    socketManager.close(effect.serverFd)
                is UnixListenDaemonEffect.ClearConnectionTables -> {
                    clientSockets.clear()
                    activeListeners.clear()
                }
                is UnixListenDaemonEffect.StopConnectionWorkers ->
                    connectionExecutor.shutdownNow()
            }
        }
    }

    public fun isGlobalShutdown(): Boolean {
        val curr = state
        return curr is UnixListenDaemonState.ShuttingDown || curr is UnixListenDaemonState.Terminated
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    public fun handleNewConnection(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>) {
        var clientFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>? = null
        try {
            while (true) {
                if (connectionAcceptor != null) {
                    clientFd = connectionAcceptor.invoke(serverFd)
                } else {
                    val res = engine.networking.accept4(
                        serverFd,
                        ManagedSegment.NULL,
                        ManagedSegment.NULL,
                        NativeConstants.SOCK_CLOEXEC,
                    )
                    val clientFdVal = res.recover { errno, _ ->
                        if (errno == NativeConstants.EINTR) return@recover -1L
                        -2L
                    }

                    if (clientFdVal == -1L) {
                        continue
                    }

                    if (clientFdVal < 0L) return
                    clientFd = FileDescriptor.adopt(clientFdVal.toInt(), FileDescriptorRole.UnixSocket)
                }

                if (clientSockets.size >= maxConnections) {
                    System.err.println("[SECCOMP-DAEMON] Rejecting connection: limit reached (${clientSockets.size})")
                    socketManager.close(clientFd)
                    return
                }

                clientSockets.add(clientFd)
                
                // Spawn a new thread to handle the connection concurrently!
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
            val pollRes = raw.poll(pollFdManaged, 1L, POLL_TIMEOUT_MS)
            val count = pollRes.recover { errno, _ ->
                if (errno == NativeConstants.EINTR) 0L else -1L
            }
            if (count < 0) {
                return applyConnectionEvent(connection, SeccompConnectionEvent.PollFailed)
            }
            if (count == 0L) {
                return applyConnectionEvent(connection, SeccompConnectionEvent.PollIdle)
            }
        }

        val event = when (connection) {
            is SeccompConnection.Accepted -> {
                val listenerFd = socketManager.recvDescriptor(socketFd)
                if (listenerFd != null) {
                    SeccompConnectionEvent.ListenerReceived(listenerFd)
                } else {
                    SeccompConnectionEvent.RecvFailed
                }
            }
            is SeccompConnection.FdAttached -> {
                val ackBuf = arena.allocate(ACK_BUF_SIZE)
                ackBuf.writeByte(0L, handshakeAckByte)
                var ackOk = false
                while (true) {
                    val res = handshakeWriter(socketFd, ackBuf, ACK_BUF_SIZE)
                    if (res is LinuxNative.SyscallResult.Success) {
                        ackOk = true
                        break
                    }
                    val errno = (res as LinuxNative.SyscallResult.Error).errno
                    if (errno == NativeConstants.EINTR) continue
                    break
                }
                if (ackOk) SeccompConnectionEvent.AckSucceeded else SeccompConnectionEvent.AckFailed
            }
            is SeccompConnection.Active -> SeccompConnectionEvent.SessionFinished
        }
        return applyConnectionEvent(connection, event)
    }

    private fun applyConnectionEvent(
        connection: SeccompConnection,
        event: SeccompConnectionEvent,
    ): SeccompConnection? {
        val transition = SeccompConnectionMachine.evaluate(connection, event)
        for (effect in transition.effects) {
            when (effect) {
                is SeccompConnectionEffect.RegisterListener -> {
                    System.err.println("[SECCOMP-DAEMON] Received listener FD: ${effect.listenerFd.value}")
                    activeListeners.add(effect.listenerFd)
                }
                is SeccompConnectionEffect.LogAck ->
                    System.err.println("[SECCOMP-DAEMON] Sending handshake ACK to socket ${connection.socketFd.value}")
                is SeccompConnectionEffect.RunSession -> {
                    val active = connection as SeccompConnection.Active
                    System.err.println("[SECCOMP-DAEMON] Starting session reactor for listener ${active.listenerFd.value}")
                    handleSession(active)
                    System.err.println("[SECCOMP-DAEMON] Session reactor finished. Closing connection.")
                }
            }
        }
        return transition.connection?.also { it.socketManager = socketManager }
    }

    private fun handleSession(connection: SeccompConnection.Active) {
        val socketFd = connection.socketFd
        val listenerFd = connection.listenerFd
        val notifHandler = notifHandlerFactory(socketFd, listenerFd)
        try {
            SeccompSessionHandler(
                socketFd = socketFd,
                listenerFd = listenerFd,
                notifHandler = notifHandler,
                onShutdown = this::triggerGlobalShutdown,
                onSocketClosed = connection::markSocketClosed,
                engine = engine,
                socketManager = socketManager,
            ).use { sessionHandler ->
                NativeArena.ofConfined().use { sessionArena ->
                    val pollFds = sessionArena.allocate(Layouts.POLLFD, 2)
                    val pfd1 = PollFdSegment.of(pollFds.asSlice(0L, Layouts.POLLFD_SIZE))
                    pfd1.setFd(listenerFd.value)
                    pfd1.setEvents(NativeConstants.POLLIN)

                    val pfd2 = PollFdSegment.of(pollFds.asSlice(POLLFD_STRUCT_SIZE, Layouts.POLLFD_SIZE))
                    pfd2.setFd(socketFd.value)
                    pfd2.setEvents(NativeConstants.POLLIN)

                    while (!isGlobalShutdown()) {
                        val pollRes = raw.poll(pollFds, 2L, POLL_TIMEOUT_MS)
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
            }
        } finally {
            (notifHandler as? AutoCloseable)?.close()
            // connection.close() in handleConnection's finally block will handle listenerFd closure.
        }
    }
}
