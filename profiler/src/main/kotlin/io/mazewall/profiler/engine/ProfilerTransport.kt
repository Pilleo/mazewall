package io.mazewall.profiler.engine

import io.mazewall.LinuxNative
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.*
import io.mazewall.getFdOrThrow
import java.nio.charset.StandardCharsets

/**
 * High-level interface for publishing domain events to the parent JVM.
 */
interface TraceEventPublisher {
    context(arena: NativeArena)
    fun sendTraceEvent(
        socketFd: FileDescriptor<*, FdState.Open>,
        event: SyscallEvent<SyscallEventState.Resolved>,
    )
}

/**
 * High-level interface for sending seccomp responses back to the kernel.
 */
interface SeccompResponder {
    /**
     * Sends a SECCOMP_USER_NOTIF_FLAG_CONTINUE response to the kernel for a successful handshake.
     * Enforced at compile-time to only work with sessions in the Success state.
     */
    context(arena: NativeArena)
    fun sendSeccompContinue(
        session: HandshakeSession.Success,
        resp: ManagedSegment,
    )

    /**
     * Sends an error response (or generic failure) to the kernel for a failed handshake.
     * Enforced at compile-time to only work with sessions in the Failed state.
     */
    context(arena: NativeArena)
    fun sendSeccompError(
        session: HandshakeSession.Failed,
        resp: ManagedSegment,
        errorNr: Int,
    )
}

/**
 * Low-level interface for raw POSIX-like polling and I/O.
 */
interface NativeIoOperations {
    val raw: io.mazewall.RawSyscallOperations
    fun poll(
        fds: ManagedSegment,
        nfds: Long,
        timeout: Int,
    ): LinuxNative.SyscallResult<Long, *>

    fun read(
        fd: FileDescriptor<*, FdState.Open>,
        buf: ManagedSegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long, *>

    fun write(
        fd: FileDescriptor<*, FdState.Open>,
        buf: ManagedSegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long, *>

    fun recv(
        sockfd: FileDescriptor<*, FdState.Open>,
        buf: ManagedSegment,
        len: Long,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, *>

    fun ioctl(
        fd: FileDescriptor<*, FdState.Open>,
        request: Long,
        arg: ManagedSegment,
    ): LinuxNative.SyscallResult<Long, *>
}

/**
 * Interface for socket creation and connection handling.
 */
typealias SocketLifecycleManager = io.mazewall.core.SocketManager

/**
 * Legacy composite interface for communicating with the parent JVM and receiving file descriptors.
 */
interface ProfilerTransport :
    TraceEventPublisher,
    SeccompResponder,
    NativeIoOperations,
    SocketLifecycleManager

/**
 * Real implementation of [ProfilerTransport] using standard Linux syscalls.
 */
@Suppress("MagicNumber", "ReturnCount", "ThrowsCount")
object RealProfilerTransport : ProfilerTransport {
    override val raw: io.mazewall.RawSyscallOperations get() = LinuxNative.raw
    private const val CMSG_LEN_VAL = 20L
    private const val CMSG_LEN_OFF = 0L
    private const val CMSG_LEVEL_OFF = 8L
    private const val CMSG_TYPE_OFF = 12L
    private const val CMSG_DATA_OFF = 16L
    private const val SOL_SOCKET_VAL = 1
    private const val SCM_RIGHTS_VAL = 1

    context(arena: NativeArena)
    override fun sendTraceEvent(
        socketFd: FileDescriptor<*, FdState.Open>,
        event: SyscallEvent<SyscallEventState.Resolved>,
    ) {
        val syscallNameBytes = event.syscallName.toByteArray(StandardCharsets.UTF_8)
        val pathBytesList = event.paths.map { it.toByteArray(StandardCharsets.UTF_8) }

        var totalSize = 4 + 4 + syscallNameBytes.size + 4 + (event.args.size * 8) + 4
        for (p in pathBytesList) {
            totalSize += 4 + p.size
        }

        val buf = ConfinedSegment(arena.arena.allocate(totalSize.toLong()))
        var offset = 0L
        buf.writeIntBigEndianUnaligned(offset, event.tid.value)
        offset += 4
        buf.writeIntBigEndianUnaligned(offset, syscallNameBytes.size)
        offset += 4
        ManagedSegment.copy(syscallNameBytes, 0, buf, offset, syscallNameBytes.size)
        offset += syscallNameBytes.size

        buf.writeIntBigEndianUnaligned(offset, event.args.size)
        offset += 4
        for (arg in event.args) {
            buf.writeLongBigEndianUnaligned(offset, arg)
            offset += 8
        }

        buf.writeIntBigEndianUnaligned(offset, pathBytesList.size)
        offset += 4
        for (p in pathBytesList) {
            buf.writeIntBigEndianUnaligned(offset, p.size)
            offset += 4
            ManagedSegment.copy(p, 0, buf, offset, p.size)
            offset += p.size
        }

        val res = LinuxNative.withTransaction { LinuxNative.memory.write(socketFd, buf, totalSize.toLong()) }
        res.getOrThrow("sendTraceEvent")
    }

    context(arena: NativeArena)
    override fun sendSeccompContinue(
        session: HandshakeSession.Success,
        resp: ManagedSegment,
    ) {
        resp.native.fill(0)
        resp.writeLong(RESP_ID_OFF, session.notifId)
        resp.writeLong(RESP_VAL_OFF, 0L)
        resp.writeInt(RESP_ERR_OFF, 0)
        resp.writeInt(RESP_FLAGS_OFF, NativeConstants.SECCOMP_USER_NOTIF_FLAG_CONTINUE.toInt())
        ioctl(session.listenerFd, SECCOMP_IOCTL_NOTIF_SEND, resp).getOrThrow("sendSeccompContinue")
    }

    context(arena: NativeArena)
    override fun sendSeccompError(
        session: HandshakeSession.Failed,
        resp: ManagedSegment,
        errorNr: Int,
    ) {
        resp.native.fill(0)
        resp.writeLong(RESP_ID_OFF, session.notifId)
        resp.writeLong(RESP_VAL_OFF, -1L)
        // Error numbers are negative in the 'error' field of seccomp_notif_resp
        resp.writeInt(RESP_ERR_OFF, -errorNr)
        resp.writeInt(RESP_FLAGS_OFF, 0)
        ioctl(session.listenerFd, SECCOMP_IOCTL_NOTIF_SEND, resp).getOrThrow("sendSeccompContinue")
    }

    override fun connect(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> {
        return io.mazewall.core.RealSocketManager.connect(socketPath)
    }

    override fun sendDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>, fdToSend: FileDescriptor<*, FdState.Open>): Boolean {
        return io.mazewall.core.RealSocketManager.sendDescriptor(socketFd, fdToSend)
    }

    override fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>? {
        return io.mazewall.core.RealSocketManager.recvDescriptor(socketFd)
    }

    override fun poll(
        fds: ManagedSegment,
        nfds: Long,
        timeout: Int,
    ): LinuxNative.SyscallResult<Long, *> = LinuxNative.withTransaction { LinuxNative.raw.poll(fds, nfds, timeout) }

    override fun read(
        fd: FileDescriptor<*, FdState.Open>,
        buf: ManagedSegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long, *> = LinuxNative.withTransaction { LinuxNative.memory.read(fd, buf, count) }

    override fun write(
        fd: FileDescriptor<*, FdState.Open>,
        buf: ManagedSegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long, *> = LinuxNative.withTransaction { LinuxNative.memory.write(fd, buf, count) }

    override fun recv(
        sockfd: FileDescriptor<*, FdState.Open>,
        buf: ManagedSegment,
        len: Long,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, *> = LinuxNative.withTransaction { LinuxNative.networking.recv(sockfd, buf, len, flags) }

    override fun ioctl(
        fd: FileDescriptor<*, FdState.Open>,
        request: Long,
        arg: ManagedSegment,
    ): LinuxNative.SyscallResult<Long, *> = LinuxNative.withTransaction { LinuxNative.raw.ioctl(fd, request, arg) }

    override fun createUnixServer(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> {
        return io.mazewall.core.RealSocketManager.createUnixServer(socketPath)
    }

    override fun accept(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> {
        return io.mazewall.core.RealSocketManager.accept(serverFd)
    }

    override fun close(fd: FileDescriptor<*, FdState.Open>) {
        LinuxNative.fileSystem.close(fd)
    }
}
