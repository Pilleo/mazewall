package io.mazewall.profiler.engine

import io.mazewall.LinuxNative
import io.mazewall.NativeEngine
import io.mazewall.core.Arch
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.SocketManager
import io.mazewall.core.Syscall
import io.mazewall.platform.daemon.UnixListenDaemonState
import io.mazewall.platform.seccomp.daemon.SeccompDaemonEngine
import io.mazewall.ffi.memory.native

/**
 * Wrapper classes that route all native I/O through the injected [ProfilerTransport]
 * to ensure the profiler daemon uses the test-provided transport for seccomp
 * notification recv/send and memory operations.
 */
internal class TransportNativeMemory(
    private val transport: ProfilerTransport,
    private val delegate: io.mazewall.NativeMemory
) : io.mazewall.NativeMemory by delegate {
    @Suppress("UNCHECKED_CAST")
    override fun read(fd: io.mazewall.core.FileDescriptor<*, io.mazewall.core.FdState.Open>, buf: io.mazewall.ffi.memory.ManagedSegment, count: Long): io.mazewall.LinuxNative.SyscallResult<Long, io.mazewall.LinuxNative.SyscallHandledState.Unhandled> =
        transport.read(fd, buf.native, count) as io.mazewall.LinuxNative.SyscallResult<Long, io.mazewall.LinuxNative.SyscallHandledState.Unhandled>
    @Suppress("UNCHECKED_CAST")
    override fun write(fd: io.mazewall.core.FileDescriptor<*, io.mazewall.core.FdState.Open>, buf: io.mazewall.ffi.memory.ManagedSegment, count: Long): io.mazewall.LinuxNative.SyscallResult<Long, io.mazewall.LinuxNative.SyscallHandledState.Unhandled> =
        transport.write(fd, buf.native, count) as io.mazewall.LinuxNative.SyscallResult<Long, io.mazewall.LinuxNative.SyscallHandledState.Unhandled>
}

internal class TransportNativeEngine(
    private val transport: ProfilerTransport,
    private val delegate: io.mazewall.NativeEngine
) : io.mazewall.NativeEngine by delegate {
    override val raw: io.mazewall.RawSyscallOperations = delegate.raw
    override val fileSystem: io.mazewall.NativeFileSystem = delegate.fileSystem
    override val process: io.mazewall.NativeProcess = delegate.process
    override val networking: io.mazewall.NativeNetworking = delegate.networking
    override val memory: io.mazewall.NativeMemory = TransportNativeMemory(transport, delegate.memory)
}

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
