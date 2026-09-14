package io.mazewall.enforcer.internal.supervisor

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

internal class SupervisorDaemonEngine(
    private val socketPath: String,
    private val engine: NativeEngine = LinuxNative,
    private val socketManager: SocketManager = io.mazewall.platform.socket.RealSocketManager,
    private val socketHelpers: SocketHelperPool = SocketHelperPool(factory = SocketHelperProcess::start),
) {
    private val admission = SupervisorAdmission()

    // Capture daemon roots before listener admission; helper classpaths/agents cannot widen them.
    private val authorityRoots = BypassPaths.safeBypassPaths.map { it.toString() }
    private val delegate = SeccompDaemonEngine(
        socketPath = socketPath,
        readySentinel = SupervisorDaemon.DAEMON_READY_SENTINEL,
        notifHandlerFactory = { socketFd, listenerFd ->
            SupervisorSessionHandler(socketFd, listenerFd, engine, socketManager, socketHelpers = socketHelpers, admission = admission, authorityRoots = authorityRoots)
        },
        handshakeAckByte = 0xAC.toByte(),
        maxConnections = MAX_CONNECTIONS,
        engine = engine,
        socketManager = socketManager,
    )

    @JvmField
    internal val clientSockets = delegate.clientSockets

    val state: UnixListenDaemonState
        get() = delegate.state

    companion object {
        private const val MAX_CONNECTIONS = 200
    }

    fun run(block: (() -> Unit)? = null) {
        try {
            socketHelpers.prewarm()
            delegate.run()
        } finally {
            socketHelpers.close()
        }
    }

    internal fun handleConnection(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>) {
        delegate.handleConnection(socketFd)
    }

    @JvmName("handleNewConnection")
    internal fun handleNewConnection(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>) {
        delegate.handleNewConnection(serverFd)
    }

    internal fun processConnectionStep(
        arena: io.mazewall.ffi.memory.NativeArena,
        connection: io.mazewall.ffi.networking.SeccompConnection,
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        pollFdManaged: io.mazewall.ffi.memory.ManagedSegment,
    ): io.mazewall.ffi.networking.SeccompConnection? {
        return delegate.processConnectionStep(arena, connection, socketFd, pollFdManaged)
    }

    fun triggerGlobalShutdown(source: String = "unknown") {
        try {
            delegate.triggerGlobalShutdown(source)
        } finally {
            socketHelpers.close()
        }
    }
}
