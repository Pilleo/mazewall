package io.mazewall.profiler.ffi

import io.mazewall.LinuxNative
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.SocketIo
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ConfinedSegment
import io.mazewall.getFdOrThrow
import io.mazewall.onFailure
import io.mazewall.onSuccess
import io.mazewall.profiler.engine.SECCOMP_IOCTL_NOTIF_SEND
import io.mazewall.profiler.engine.SyscallEvent
import io.mazewall.profiler.engine.SyscallEventState
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.charset.StandardCharsets

/**
 * High-level interface for publishing domain events to the parent JVM.
 */
internal interface TraceEventPublisher {
    context(arena: Arena) fun sendTraceEvent(
        socketFd: FileDescriptor<*, FdState.Open, FdOwnership>,
        event: SyscallEvent<SyscallEventState.Resolved>,
    )
}

/**
 * High-level interface for sending seccomp responses back to the kernel.
 */
internal interface SeccompResponder {
    /**
     * Sends a SECCOMP_USER_NOTIF_FLAG_CONTINUE response to the kernel for a successful handshake.
     * Enforced at compile-time to only work with sessions in the Success state.
     */
    context(arena: Arena) fun sendSeccompContinue(
        session: HandshakeSession.Success,
        resp: MemorySegment,
    )

    /**
     * Sends an error response (or generic failure) to the kernel for a failed handshake.
     * Enforced at compile-time to only work with sessions in the Failed state.
     */
    context(arena: Arena) fun sendSeccompError(
        session: HandshakeSession.Failed,
        resp: MemorySegment,
        errorNr: Int,
    )
}

/**
 * Low-level interface for raw POSIX-like polling and I/O.
 */
internal interface NativeIoOperations {
    val raw: io.mazewall.RawSyscallOperations

    fun poll(
        fds: MemorySegment,
        nfds: Long,
        timeout: Int,
    ): LinuxNative.SyscallResult<Long, *>

    fun read(
        fd: FileDescriptor<*, FdState.Open, FdOwnership>,
        buf: MemorySegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long, *>

    fun write(
        fd: FileDescriptor<*, FdState.Open, FdOwnership>,
        buf: MemorySegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long, *>

    fun recv(
        sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
        buf: MemorySegment,
        len: Long,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, *>

    fun ioctl(
        fd: FileDescriptor<*, FdState.Open, FdOwnership>,
        request: Long,
        arg: MemorySegment,
    ): LinuxNative.SyscallResult<Long, *>

    fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>)
}

/**
 * Interface for socket creation and connection handling.
 */
typealias SocketLifecycleManager = io.mazewall.core.SocketManager

/**
 * Legacy composite interface for communicating with the parent JVM and receiving file descriptors.
 */
internal interface ProfilerTransport :
    TraceEventPublisher,
    SeccompResponder,
    NativeIoOperations,
    SocketLifecycleManager

/**
 * Real implementation of [ProfilerTransport] using standard Linux syscalls.
 */

internal object RealProfilerTransport : ProfilerTransport {
    private val logger = java.util.logging.Logger
        .getLogger(RealProfilerTransport::class.java.name)

    override val raw: io.mazewall.RawSyscallOperations get() = LinuxNative.raw

    context(arena: Arena) override fun sendTraceEvent(
        socketFd: FileDescriptor<*, FdState.Open, FdOwnership>,
        event: SyscallEvent<SyscallEventState.Resolved>,
    ) {
        val bytes = io.mazewall.profiler.internal.TraceEventEncoder
            .encode(event)
        val buf = arena.allocate(bytes.size.toLong())
        MemorySegment.copy(bytes, 0, buf, ValueLayout.JAVA_BYTE, 0, bytes.size)
        SocketIo
            .writeFully(
            LinuxNative.memory,
            socketFd,
            ConfinedSegment(buf),
            bytes.size.toLong(),
        ).getOrThrow("sendTraceEvent")
    }

    context(arena: Arena) override fun sendSeccompContinue(
        session: HandshakeSession.Success,
        resp: MemorySegment,
    ) {
        io.mazewall.platform.seccomp.UserNotifReply.encodeContinue(
            ConfinedSegment(resp),
            session.notifId,
        )
        val res = ioctl(session.listenerFd, SECCOMP_IOCTL_NOTIF_SEND, resp)
        if (res is LinuxNative.SyscallResult.Error) {
            logger.warning("SECCOMP_IOCTL_NOTIF_SEND failed with errno=${res.errno}")
            if (res.errno == NativeConstants.ENOENT) {
                return
            }
        }
        res.getOrThrow("sendSeccompContinue")
    }

    context(arena: Arena) override fun sendSeccompError(
        session: HandshakeSession.Failed,
        resp: MemorySegment,
        errorNr: Int,
    ) {
        io.mazewall.platform.seccomp.UserNotifReply.encodeError(
            ConfinedSegment(resp),
            session.notifId,
            errorNr,
        )
        val res = ioctl(session.listenerFd, SECCOMP_IOCTL_NOTIF_SEND, resp)
        if (res is LinuxNative.SyscallResult.Error) {
            logger.warning("SECCOMP_IOCTL_NOTIF_SEND failed with errno=${res.errno}")
            if (res.errno == NativeConstants.ENOENT) {
                return
            }
        }
        res.getOrThrow("sendSeccompError")
    }

    override fun connect(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> {
        return io.mazewall.core.RealSocketManager
            .connect(socketPath)
    }

    override fun sendDescriptor(
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership>,
        fdToSend: FileDescriptor<*, FdState.Open, FdOwnership>,
    ): Boolean {
        return io.mazewall.core.RealSocketManager
            .sendDescriptor(socketFd, fdToSend)
    }

    override fun recvDescriptor(
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership>,
    ): FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>? {
        return io.mazewall.core.RealSocketManager
            .recvDescriptor(socketFd)
    }

    override fun <R : FileDescriptorRole> recvDescriptor(
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership>,
        role: R,
    ): FileDescriptor<R, FdState.Open, FdOwnership.Owned>? {
        return io.mazewall.core.RealSocketManager
            .recvDescriptor(socketFd, role)
    }

    override fun poll(
        fds: MemorySegment,
        nfds: Long,
        timeout: Int,
    ): LinuxNative.SyscallResult<Long, *> = LinuxNative.raw.poll(ConfinedSegment(fds), nfds, timeout)

    override fun read(
        fd: FileDescriptor<*, FdState.Open, FdOwnership>,
        buf: MemorySegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long, *> = LinuxNative.memory.read(fd, ConfinedSegment(buf), count)

    override fun write(
        fd: FileDescriptor<*, FdState.Open, FdOwnership>,
        buf: MemorySegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long, *> = LinuxNative.memory.write(fd, ConfinedSegment(buf), count)

    override fun recv(
        sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
        buf: MemorySegment,
        len: Long,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, *> = LinuxNative.networking.recv(sockfd, ConfinedSegment(buf), len, flags)

    override fun ioctl(
        fd: FileDescriptor<*, FdState.Open, FdOwnership>,
        request: Long,
        arg: MemorySegment,
    ): LinuxNative.SyscallResult<Long, *> = LinuxNative.raw.ioctl(fd, request, ConfinedSegment(arg))

    override fun createUnixServer(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> {
        return io.mazewall.core.RealSocketManager
            .createUnixServer(socketPath)
    }

    override fun accept(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> {
        return io.mazewall.core.RealSocketManager
            .accept(serverFd)
    }

    override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>) {
        io.mazewall.core.RealSocketManager
            .close(fd)
    }
}
