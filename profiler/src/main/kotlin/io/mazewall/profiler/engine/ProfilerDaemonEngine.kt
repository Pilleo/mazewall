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
import io.mazewall.ffi.memory.ConfinedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.SegmentPool
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.unwrap
import io.mazewall.ffi.memory.writeInt
import io.mazewall.ffi.memory.writeShort
import io.mazewall.ffi.memory.writeByte
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Standalone Profiler Daemon Engine.
 *
 * Communicates with the parent JVM via a [ProfilerTransport], sending binary [SyscallEvent]
 * structures and resolving memory using [ProfilerMemoryReader].
 */
// @ref: docs/internals/designs/profiler/profiler-design.md — USER_NOTIF ACK loop protocol, deadlock prevention, SCM_RIGHTS socket FD transfer
// @ref: docs/internals/designs/core/architectural-map.md — Profiler-Enforcer ACK loop sequence diagram
public class ProfilerDaemonEngine(
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

    internal var state: ProfilerDaemonState
        get() = stateRef.get()
        private set(value) = stateRef.set(value)

    companion object {
        private const val DAEMON_READY_SENTINEL = "MAZEWALL_DAEMON_READY"
        private const val POLL_TIMEOUT_MS = 200
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
        val pollFd = io.mazewall.ffi.memory.PollFdSegment.of(arena.allocate(Layouts.POLLFD))
        pollFd.setFd(serverFd.value)
        pollFd.setEvents(NativeConstants.POLLIN)

        while (!isGlobalShutdown()) {
            val pollRes = ioOps.raw.poll(pollFd.managed, 1L, POLL_TIMEOUT_MS)
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
        var clientFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>? = null
        try {
            clientFd = socketManager.accept(serverFd)
            clientSockets.add(clientFd)
            Thread { handleConnection(clientFd) }.apply {
                name = "conn-handler-${clientFd.value}"
                start()
            }
        } catch (e: InterruptedException) {
            System.err.println("WARN: handleNewConnection interrupted: ${e.message}")
            if (clientFd != null) {
                clientSockets.remove(clientFd)
                try { socketManager.close(clientFd) } catch (_: Exception) {}
            }
            Thread.currentThread().interrupt()
        } catch (e: java.nio.channels.ClosedByInterruptException) {
            System.err.println("WARN: handleNewConnection channel closed by interrupt: ${e.message}")
            if (clientFd != null) {
                clientSockets.remove(clientFd)
                try { socketManager.close(clientFd) } catch (_: Exception) {}
            }
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            System.err.println("WARN: Failed to start connection handler thread: ${t.message}")
            if (clientFd != null) {
                clientSockets.remove(clientFd)
                try { socketManager.close(clientFd) } catch (_: Exception) {}
            }
            if (t is Error) throw t
        }
    }

    @Suppress("NestedBlockDepth", "LoopWithTooManyJumpStatements", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
    internal fun handleConnection(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>) {
        var connection: io.mazewall.ffi.networking.SeccompConnection = io.mazewall.ffi.networking.SeccompConnection.Accepted(socketFd)
        try {
            NativeArena.ofConfined().use { arena ->
                val pollFd = io.mazewall.ffi.memory.PollFdSegment.of(arena.allocate(Layouts.POLLFD))
                pollFd.setFd(socketFd.value)
                pollFd.setEvents(NativeConstants.POLLIN)

                while (!isGlobalShutdown()) {
                    // Only poll if we are waiting for a NEW listener FD (Accepted state)
                    if (connection is io.mazewall.ffi.networking.SeccompConnection.Accepted) {
                        val pollRes = ioOps.raw.poll(pollFd.managed, 1L, POLL_TIMEOUT_MS)
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
                            val ackBuf = arena.allocate(ACK_BUF_SIZE).unwrap
                            ackBuf.set(ValueLayout.JAVA_BYTE, 0L, PROTOCOL_ACK_BYTE)
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
        } catch (e: InterruptedException) {
            System.err.println("[DAEMON] Connection handler interrupted: ${e.message}")
            Thread.currentThread().interrupt()
        } catch (e: java.nio.channels.ClosedByInterruptException) {
            System.err.println("[DAEMON] Connection handler channel closed by interrupt: ${e.message}")
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            System.err.println("[DAEMON-WARN] Connection handler terminated with exception: ${e.message}")
        } finally {
            clientSockets.remove(socketFd)
            socketManager.close(socketFd)

            val lFd = when (val c = connection) {
                is io.mazewall.ffi.networking.SeccompConnection.FdAttached -> c.listenerFd
                is io.mazewall.ffi.networking.SeccompConnection.Active -> c.listenerFd
                else -> null
            }
            if (lFd != null && activeListeners.contains(lFd)) {
                activeListeners.remove(lFd)
                try {
                    socketManager.close(lFd)
                } catch (_: Exception) {}
            }
        }
    }

    private fun handleSession(
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
    ) {
        ProfilerSessionHandler(
            socketFd = socketFd,
            listenerFd = listenerFd,
            publisher = publisher,
            responder = responder,
            ioOps = ioOps,
            memoryReader = memoryReader,
            syscallMap = syscallMap,
            onShutdown = this::triggerGlobalShutdown,
        ).use { sessionHandler ->
            try {
                NativeArena.ofConfined().use { sessionArena ->
                    val pollFds = with(sessionArena) { setupSessionPoll(socketFd, listenerFd) }

                    while (!isGlobalShutdown()) {
                        val pollRes = ioOps.raw.poll(pollFds, 2L, POLL_TIMEOUT_MS)
                        val count = pollRes.recover { errno, _ ->
                            if (errno != NativeConstants.EINTR) return@use // Break from loop
                            0L
                        }
                        if (count <= 0) continue

                        NativeArena.ofConfined().use { iterationArena ->
                            val action = with(iterationArena) {
                                sessionHandler.handleActiveListener(pollFds)
                            }
                            if (action !is LoopAction.Continue) break
                        }
                        if (isGlobalShutdown()) break
                    }
                }
            } finally {
                activeListeners.remove(listenerFd)
                socketManager.close(listenerFd)
            }
        }
    }

    context(arena: NativeArena)
    private fun setupSessionPoll(
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
    ): ManagedSegment {
        val pollFds = arena.allocate(MemoryLayout.sequenceLayout(2, Layouts.POLLFD))
        // [0]: Seccomp listener FD
        pollFds.writeInt(0L, listenerFd.value)
        pollFds.writeShort(POLLFD_EVENTS_OFF, NativeConstants.POLLIN)
        // [1]: UNIX socket FD (for parent shutdown/ACK)
        pollFds.writeInt(POLLFD_STRUCT_SIZE, socketFd.value)
        pollFds.writeShort(POLLFD_STRUCT_SIZE + POLLFD_EVENTS_OFF, NativeConstants.POLLIN)
        return pollFds
    }
}
