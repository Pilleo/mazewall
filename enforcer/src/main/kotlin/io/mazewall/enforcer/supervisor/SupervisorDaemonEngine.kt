package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.NativeEngine
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.SocketManager
import io.mazewall.platform.seccomp.daemon.SeccompDaemonEngine
import io.mazewall.platform.seccomp.daemon.SeccompDaemonState

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
    private val delegate = SeccompDaemonEngine(

        socketPath = socketPath,
        readySentinel = SupervisorDaemon.DAEMON_READY_SENTINEL,
        notifHandlerFactory = { socketFd, listenerFd ->
            SupervisorSessionHandler(socketFd, listenerFd, engine, socketManager)
        },
        handshakeAckByte = 0xAC.toByte(),
        maxConnections = MAX_CONNECTIONS,
        engine = engine,
        socketManager = socketManager
    )

    @JvmField
    internal val clientSockets = delegate.clientSockets


    val state: SupervisorDaemonState
        get() = when (val s = delegate.state) {
            is SeccompDaemonState.Uninitialized -> SupervisorDaemonState.Uninitialized
            is SeccompDaemonState.Listening -> SupervisorDaemonState.Listening(s.serverFd, s.socketPath)
            is SeccompDaemonState.Active -> SupervisorDaemonState.Active(s.serverFd, s.socketPath)
            is SeccompDaemonState.ShuttingDown -> SupervisorDaemonState.ShuttingDown
            is SeccompDaemonState.Terminated -> SupervisorDaemonState.Terminated
        }

    companion object {
        private const val MAX_CONNECTIONS = 200
    }

    fun run(block: (() -> Unit)? = null) {
        delegate.run()
    }

    internal fun handleConnection(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>) {
        delegate.handleConnection(socketFd)
    }

    @JvmName("handleNewConnection")
    internal fun handleNewConnection(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>) {
        delegate.handleNewConnection(serverFd)
    }





    internal fun processConnectionStep(
        arena: io.mazewall.ffi.memory.NativeArena,
        connection: io.mazewall.ffi.networking.SeccompConnection,
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        pollFdManaged: io.mazewall.ffi.memory.ManagedSegment
    ): io.mazewall.ffi.networking.SeccompConnection? {
        return delegate.processConnectionStep(arena, connection, socketFd, pollFdManaged)
    }

    fun triggerGlobalShutdown(source: String = "unknown") {


        delegate.triggerGlobalShutdown(source)
    }
}

