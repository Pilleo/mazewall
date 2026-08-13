package io.mazewall.profiler.engine

import io.mazewall.LinuxNative
import io.mazewall.NativeEngine
import io.mazewall.core.Arch
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.RealSocketManager
import io.mazewall.core.SocketManager
import io.mazewall.core.Syscall
import io.mazewall.platform.seccomp.daemon.SeccompDaemonEngine
import io.mazewall.platform.seccomp.daemon.SeccompDaemonState
import io.mazewall.ffi.memory.native

/**
 * Standalone Profiler Daemon Engine.
 *
 * Communicates with the parent JVM via a [ProfilerTransport], sending binary [SyscallEvent]
 * structures and resolving memory using [ProfilerMemoryReader].
 */
public class ProfilerDaemonEngine(
    private val socketPath: String,
    private val transport: ProfilerTransport = RealProfilerTransport,
    private val memoryReader: ProfilerMemoryReader = RealMemoryReader,
    private val engine: NativeEngine = LinuxNative,
    private val socketManager: SocketManager = transport,
) {

    private val publisher: TraceEventPublisher = transport
    private val responder: SeccompResponder = transport
    private val ioOps: NativeIoOperations = transport

    private val syscallMap = mutableMapOf<Int, String>()

    init {
        val arch = Arch.current()
        for (s in Syscall.entries) {
            val nr = s.numberFor(arch)
            if (nr >= 0) syscallMap[nr] = s.name
        }
    }

    private val delegate = SeccompDaemonEngine(
        socketPath = socketPath,
        readySentinel = DAEMON_READY_SENTINEL,
        notifHandlerFactory = { socketFd, listenerFd ->
            ProfilerSessionHandler(
                socketFd = socketFd,
                listenerFd = listenerFd,
                publisher = publisher,
                responder = responder,
                ioOps = ioOps,
                memoryReader = memoryReader,
                syscallMap = syscallMap,
                onShutdown = { source -> triggerGlobalShutdown(source) }
            )
        },
        maxConnections = MAX_CONNECTIONS,
        engine = engine,
        socketManager = socketManager,
        raw = transport.raw,
        handshakeWriter = { fd, buffer, count -> transport.write(fd, buffer.native, count) },
        connectionAcceptor = transport::accept,
    )

    @JvmField
    internal val clientSockets = delegate.clientSockets


    internal val state: ProfilerDaemonState
        get() = when (val s = delegate.state) {
            is SeccompDaemonState.Uninitialized -> ProfilerDaemonState.Uninitialized
            is SeccompDaemonState.Listening -> ProfilerDaemonState.Listening(s.serverFd, s.socketPath)
            is SeccompDaemonState.Active -> ProfilerDaemonState.Active(s.serverFd)
            is SeccompDaemonState.ShuttingDown -> ProfilerDaemonState.ShuttingDown
            is SeccompDaemonState.Terminated -> ProfilerDaemonState.Terminated
        }

    companion object {
        private const val DAEMON_READY_SENTINEL = "MAZEWALL_DAEMON_READY"
        private const val MAX_CONNECTIONS = 200
    }

    fun run(block: (() -> Unit)? = null) {
        block?.invoke()
        delegate.run()
    }

    internal fun handleConnection(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>) {
        delegate.handleConnection(socketFd)
    }

    @JvmName("handleNewConnection")
    internal fun handleNewConnection(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>) {
        delegate.handleNewConnection(serverFd)
    }






    fun triggerGlobalShutdown(source: String = "unknown") {

        delegate.triggerGlobalShutdown(source)
    }
}
