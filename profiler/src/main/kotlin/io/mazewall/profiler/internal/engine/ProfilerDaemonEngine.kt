package io.mazewall.profiler.internal.engine

import io.mazewall.ffi.memory.native
import io.mazewall.platform.daemon.UnixListenDaemonState
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.seccomp.daemon.SeccompDaemonEngine
import io.mazewall.platform.socket.SocketManager
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.Syscall
import io.mazewall.profiler.api.PathResolution
import io.mazewall.profiler.api.PathResolutionCause
import io.mazewall.profiler.api.PathResolutionFailure
import io.mazewall.profiler.api.SyscallEvent
import io.mazewall.profiler.api.SyscallEventState
import io.mazewall.profiler.api.combinePathResolution
import io.mazewall.profiler.ffi.NativeIoOperations
import io.mazewall.profiler.ffi.ProfilerTransport
import io.mazewall.profiler.ffi.RealProfilerTransport
import io.mazewall.profiler.ffi.SeccompResponder
import io.mazewall.profiler.ffi.TraceEventPublisher
import io.mazewall.profiler.ffi.TransportNativeEngine

/**
 * Standalone observation daemon; structural/transport failures retain exactly-one terminal reply semantics.
 *
 * Communicates with the parent JVM via a [ProfilerTransport], sending binary [SyscallEvent]
 * structures and resolving memory using [ProfilerMemoryReader].
 */
internal class ProfilerDaemonEngine internal constructor(
    private val socketPath: String,
    private val transport: ProfilerTransport = RealProfilerTransport,
    private val memoryReader: ProfilerMemoryReader = RealMemoryReader,
    private val engine: NativeEngine = LinuxNative,
    private val socketManager: SocketManager = transport,
) {
    public constructor(socketPath: String) : this(socketPath, RealProfilerTransport)

    private val publisher: TraceEventPublisher = transport
    private val responder: SeccompResponder = transport
    private val ioOps: NativeIoOperations = transport
    private val preparation = ProfilerPreparationPool()

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
                preparation = preparation,
                onShutdown = { source -> triggerGlobalShutdown(source) },
            )
        },
        maxConnections = MAX_CONNECTIONS,
        engine = TransportNativeEngine(transport, engine),
        socketManager = socketManager,
        raw = transport.raw,
        handshakeWriter = { fd, buffer, count -> transport.write(fd, buffer.native, count) },
        connectionAcceptor = transport::accept,
    )

    @JvmField
    internal val clientSockets = delegate.clientSockets

    internal val state: UnixListenDaemonState
        get() = delegate.state

    companion object {
        private const val DAEMON_READY_SENTINEL = "MAZEWALL_DAEMON_READY"
        private const val MAX_CONNECTIONS = 200
    }

    fun run(block: (() -> Unit)? = null) {
        block?.invoke()
        preparation.use {
            delegate.run()
        }
    }

    internal fun handleConnection(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>) {
        delegate.handleConnection(socketFd)
    }

    @JvmName("handleNewConnection")
    internal fun handleNewConnection(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>) {
        delegate.handleNewConnection(serverFd)
    }

    fun triggerGlobalShutdown(source: String = "unknown") {
        delegate.triggerGlobalShutdown(source)
    }
}
