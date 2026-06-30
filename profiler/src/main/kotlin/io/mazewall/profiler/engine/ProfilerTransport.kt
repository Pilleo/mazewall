package io.mazewall.profiler.engine

import io.mazewall.LinuxNative
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.getFdOrThrow
import io.mazewall.onFailure
import io.mazewall.onSuccess
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.charset.StandardCharsets

/**
 * High-level interface for publishing domain events to the parent JVM.
 */
interface TraceEventPublisher {
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
     * Sends a SECCOMP_USER_NOTIF_FLAG_CONTINUE response to the kernel.
     */
    fun sendSeccompContinue(
        notifId: Long,
        listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
        resp: MemorySegment,
    )

    /**
     * Sends an error response (or generic failure) to the kernel.
     */
    fun sendSeccompError(
        notifId: Long,
        listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
        resp: MemorySegment,
        errorNr: Int,
    )
}

/**
 * Low-level interface for raw POSIX-like polling and I/O.
 */
interface NativeIoOperations {
    fun poll(
        fds: MemorySegment,
        nfds: Long,
        timeout: Int,
    ): LinuxNative.SyscallResult<Long, *>

    fun read(
        fd: FileDescriptor<*, FdState.Open>,
        buf: MemorySegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long, *>

    fun write(
        fd: FileDescriptor<*, FdState.Open>,
        buf: MemorySegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long, *>

    fun recv(
        sockfd: FileDescriptor<*, FdState.Open>,
        buf: MemorySegment,
        len: Long,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, *>

    fun ioctl(
        fd: FileDescriptor<*, FdState.Open>,
        request: Long,
        arg: MemorySegment,
    ): LinuxNative.SyscallResult<Long, *>
}

/**
 * Interface for socket creation and connection handling.
 */
interface SocketLifecycleManager {
    fun createServer(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>

    fun accept(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>

    fun close(fd: FileDescriptor<*, FdState.Open>)

    fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>?
}

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
    private const val CMSG_LEN_VAL = 20L
    private const val CMSG_LEN_OFF = 0L
    private const val CMSG_LEVEL_OFF = 8L
    private const val CMSG_TYPE_OFF = 12L
    private const val CMSG_DATA_OFF = 16L
    private const val SOL_SOCKET_VAL = 1
    private const val SCM_RIGHTS_VAL = 1

    private val JAVA_INT_BE_UNALIGNED = ValueLayout.JAVA_INT.withOrder(java.nio.ByteOrder.BIG_ENDIAN).withByteAlignment(1)
    private val JAVA_LONG_BE_UNALIGNED = ValueLayout.JAVA_LONG.withOrder(java.nio.ByteOrder.BIG_ENDIAN).withByteAlignment(1)

    override fun sendTraceEvent(
        socketFd: FileDescriptor<*, FdState.Open>,
        event: SyscallEvent<SyscallEventState.Resolved>,
    ) {
        Arena.ofConfined().use { arena ->
            val syscallNameBytes = event.syscallName.toByteArray(StandardCharsets.UTF_8)
            val pathBytesList = event.paths.map { it.toByteArray(StandardCharsets.UTF_8) }

            var totalSize = 4 + 4 + syscallNameBytes.size + 4 + (event.args.size * 8) + 4
            for (p in pathBytesList) {
                totalSize += 4 + p.size
            }

            val buf = arena.allocate(totalSize.toLong())
            var offset = 0L
            buf.set(JAVA_INT_BE_UNALIGNED, offset, event.tid.value)
            offset += 4
            buf.set(JAVA_INT_BE_UNALIGNED, offset, syscallNameBytes.size)
            offset += 4
            MemorySegment.copy(syscallNameBytes, 0, buf, ValueLayout.JAVA_BYTE, offset, syscallNameBytes.size)
            offset += syscallNameBytes.size

            buf.set(JAVA_INT_BE_UNALIGNED, offset, event.args.size)
            offset += 4
            for (arg in event.args) {
                buf.set(JAVA_LONG_BE_UNALIGNED, offset, arg)
                offset += 8
            }

            buf.set(JAVA_INT_BE_UNALIGNED, offset, pathBytesList.size)
            offset += 4
            for (p in pathBytesList) {
                buf.set(JAVA_INT_BE_UNALIGNED, offset, p.size)
                offset += 4
                MemorySegment.copy(p, 0, buf, ValueLayout.JAVA_BYTE, offset, p.size)
                offset += p.size
            }

            val res = LinuxNative.withTransaction { LinuxNative.memory.write(socketFd, buf, totalSize.toLong()) }
            res.getOrThrow("sendTraceEvent")
        }
    }

    override fun sendSeccompContinue(
        notifId: Long,
        listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
        resp: MemorySegment,
    ) {
        resp.fill(0)
        resp.set(ValueLayout.JAVA_LONG, RESP_ID_OFF, notifId)
        resp.set(ValueLayout.JAVA_LONG, RESP_VAL_OFF, 0L)
        resp.set(ValueLayout.JAVA_INT, RESP_ERR_OFF, 0)
        resp.set(ValueLayout.JAVA_INT, RESP_FLAGS_OFF, NativeConstants.SECCOMP_USER_NOTIF_FLAG_CONTINUE.toInt())
        ioctl(listenerFd, SECCOMP_IOCTL_NOTIF_SEND, resp).getOrThrow("sendSeccompContinue")
    }

    override fun sendSeccompError(
        notifId: Long,
        listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
        resp: MemorySegment,
        errorNr: Int,
    ) {
        resp.fill(0)
        resp.set(ValueLayout.JAVA_LONG, RESP_ID_OFF, notifId)
        resp.set(ValueLayout.JAVA_LONG, RESP_VAL_OFF, -1L)
        // Error numbers are negative in the 'error' field of seccomp_notif_resp
        resp.set(ValueLayout.JAVA_INT, RESP_ERR_OFF, -errorNr)
        resp.set(ValueLayout.JAVA_INT, RESP_FLAGS_OFF, 0)
        ioctl(listenerFd, SECCOMP_IOCTL_NOTIF_SEND, resp).getOrThrow("sendSeccompContinue")
    }

    override fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>? {
        return io.mazewall.ffi.networking.SupervisorSocketUtils.recvDescriptor(socketFd)
    }

    override fun poll(
        fds: MemorySegment,
        nfds: Long,
        timeout: Int,
    ): LinuxNative.SyscallResult<Long, *> = LinuxNative.withTransaction { LinuxNative.poll(fds, nfds, timeout) }

    override fun read(
        fd: FileDescriptor<*, FdState.Open>,
        buf: MemorySegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long, *> = LinuxNative.withTransaction { LinuxNative.memory.read(fd, buf, count) }

    override fun write(
        fd: FileDescriptor<*, FdState.Open>,
        buf: MemorySegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long, *> = LinuxNative.withTransaction { LinuxNative.memory.write(fd, buf, count) }

    override fun recv(
        sockfd: FileDescriptor<*, FdState.Open>,
        buf: MemorySegment,
        len: Long,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, *> = LinuxNative.withTransaction { LinuxNative.networking.recv(sockfd, buf, len, flags) }

    override fun ioctl(
        fd: FileDescriptor<*, FdState.Open>,
        request: Long,
        arg: MemorySegment,
    ): LinuxNative.SyscallResult<Long, *> = LinuxNative.withTransaction { LinuxNative.ioctl(fd, request, arg) }

    override fun createServer(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> {
        val fd = LinuxNative.withTransaction {
            LinuxNative.networking.socket(
                io.mazewall.ffi.networking.SupervisorSocketUtils.AF_UNIX,
                io.mazewall.ffi.networking.SupervisorSocketUtils.SOCK_STREAM,
                0
            )
        }.getFdOrThrow("socket(AF_UNIX)").let { FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(it.value) }

        Arena.ofConfined().use { arena ->
            val sockaddrUn = io.mazewall.ffi.networking.SupervisorSocketUtils.setupSockAddrUn(arena, socketPath)

            LinuxNative.withTransaction {
                LinuxNative.networking.bind(fd, sockaddrUn.segment, io.mazewall.ffi.networking.SupervisorSocketUtils.SOCKADDR_UN_SIZE)
            }.onFailure { _, _ ->
                LinuxNative.fileSystem.close(fd)
            }.getOrThrow("bind(AF_UNIX)")
        }

        LinuxNative.withTransaction {
            LinuxNative.networking.listen(fd, io.mazewall.ffi.networking.SupervisorSocketUtils.BACKLOG_SIZE)
        }.onFailure { _, _ ->
            LinuxNative.fileSystem.close(fd)
        }.getOrThrow("listen")

        return fd
    }

    override fun accept(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open> {
        val res = LinuxNative.withTransaction { LinuxNative.networking.accept(serverFd, MemorySegment.NULL, MemorySegment.NULL) }
        return res.getFdOrThrow("accept").let { FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(it.value) }
    }

    override fun close(fd: FileDescriptor<*, FdState.Open>) {
        LinuxNative.fileSystem.close(fd)
    }
}
