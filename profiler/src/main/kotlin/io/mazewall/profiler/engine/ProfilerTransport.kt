package io.mazewall.profiler.ffi

import io.mazewall.LinuxNative
import io.mazewall.NativeEngine
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.SocketIo
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ConfinedSegment
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.native
import io.mazewall.getFdOrThrow
import io.mazewall.profiler.engine.SyscallEvent
import io.mazewall.profiler.engine.SyscallEventState
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

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
    ): SeccompReplyResult

    /**
     * Sends an error response (or generic failure) to the kernel for a failed handshake.
     * Enforced at compile-time to only work with sessions in the Failed state.
     */
    context(arena: Arena) fun sendSeccompError(
        session: HandshakeSession.Failed,
        resp: MemorySegment,
        errorNr: Int,
    ): SeccompReplyResult
}

/** ENOENT is an expired notification, never evidence of a successful reply. */
internal enum class SeccompReplyResult { SENT, NOTIFICATION_GONE }

/** Native-only bridge for the shared reactor; injected transport operations remain authoritative. */
internal class TransportNativeEngine(
    transport: ProfilerTransport,
    delegate: NativeEngine,
) : NativeEngine by delegate {
    override val raw: io.mazewall.RawSyscallOperations = transport.raw
    override val memory: io.mazewall.NativeMemory = object : io.mazewall.NativeMemory by delegate.memory {
        override fun read(
            fd: FileDescriptor<*, FdState.Open, FdOwnership>,
            buf: ManagedSegment,
            count: Long,
        ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
            transport.read(fd, buf.native, count) as LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>

        override fun write(
            fd: FileDescriptor<*, FdState.Open, FdOwnership>,
            buf: ManagedSegment,
            count: Long,
        ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
            transport.write(fd, buf.native, count) as LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>
    }
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

internal object RealProfilerTransport : ProfilerTransport by NativeProfilerTransport(LinuxNative, io.mazewall.core.RealSocketManager)

internal class NativeProfilerTransport(
    private val engine: NativeEngine,
    private val sockets: io.mazewall.core.SocketManager,
) : ProfilerTransport,
    SocketLifecycleManager by sockets {
    private val logger = java.util.logging.Logger
        .getLogger(RealProfilerTransport::class.java.name)

    override val raw: io.mazewall.RawSyscallOperations get() = engine.raw

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
            engine.memory,
            socketFd,
            ConfinedSegment(buf),
            bytes.size.toLong(),
        ).getOrThrow("sendTraceEvent")
    }

    context(arena: Arena) override fun sendSeccompContinue(
        session: HandshakeSession.Success,
        resp: MemorySegment,
    ): SeccompReplyResult {
        io.mazewall.platform.seccomp.UserNotifReply.encodeContinue(
            ConfinedSegment(resp),
            session.notifId,
        )
        return sendReply(session.listenerFd, resp)
    }

    context(arena: Arena) override fun sendSeccompError(
        session: HandshakeSession.Failed,
        resp: MemorySegment,
        errorNr: Int,
    ): SeccompReplyResult {
        io.mazewall.platform.seccomp.UserNotifReply.encodeError(
            ConfinedSegment(resp),
            session.notifId,
            errorNr,
        )
        return sendReply(session.listenerFd, resp)
    }

    private fun sendReply(
        listener: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership>,
        resp: MemorySegment,
    ): SeccompReplyResult {
        return when (
            val outcome = io.mazewall.platform.seccomp.UserNotifReply
            .send(raw, listener, ConfinedSegment(resp))
        ) {
            io.mazewall.platform.seccomp.UserNotifReplyResult.Sent -> SeccompReplyResult.SENT
            io.mazewall.platform.seccomp.UserNotifReplyResult.NotificationGone -> {
                logger.warning("SECCOMP_IOCTL_NOTIF_SEND failed with errno=${NativeConstants.ENOENT}")
                SeccompReplyResult.NOTIFICATION_GONE
            }
            is io.mazewall.platform.seccomp.UserNotifReplyResult.Failed -> {
                logger.warning("SECCOMP_IOCTL_NOTIF_SEND failed with errno=${outcome.errno}")
                error("SECCOMP_IOCTL_NOTIF_SEND failed with errno=${outcome.errno}")
            }
        }
    }

    override fun poll(
        fds: MemorySegment,
        nfds: Long,
        timeout: Int,
    ): LinuxNative.SyscallResult<Long, *> = raw.poll(ConfinedSegment(fds), nfds, timeout)

    override fun read(
        fd: FileDescriptor<*, FdState.Open, FdOwnership>,
        buf: MemorySegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long, *> = engine.memory.read(fd, ConfinedSegment(buf), count)

    override fun write(
        fd: FileDescriptor<*, FdState.Open, FdOwnership>,
        buf: MemorySegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long, *> = engine.memory.write(fd, ConfinedSegment(buf), count)

    override fun recv(
        sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
        buf: MemorySegment,
        len: Long,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, *> = engine.networking.recv(sockfd, ConfinedSegment(buf), len, flags)

    override fun ioctl(
        fd: FileDescriptor<*, FdState.Open, FdOwnership>,
        request: Long,
        arg: MemorySegment,
    ): LinuxNative.SyscallResult<Long, *> = raw.ioctl(fd, request, ConfinedSegment(arg))

    override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>) {
        sockets.close(fd)
    }
}
