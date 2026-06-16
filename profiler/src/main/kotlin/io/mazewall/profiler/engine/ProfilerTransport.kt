package io.mazewall.profiler.engine

import io.mazewall.LinuxNative
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
        socketFd: FileDescriptor<*>,
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
    fun sendSeccompContinue(
        session: HandshakeSession.Success,
        resp: MemorySegment,
    )

    /**
     * Sends an error response (or generic failure) to the kernel for a failed handshake.
     * Enforced at compile-time to only work with sessions in the Failed state.
     */
    fun sendSeccompError(
        session: HandshakeSession.Failed,
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
        fd: FileDescriptor<*>,
        buf: MemorySegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long, *>

    fun write(
        fd: FileDescriptor<*>,
        buf: MemorySegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long, *>

    fun recv(
        sockfd: FileDescriptor<*>,
        buf: MemorySegment,
        len: Long,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, *>

    fun ioctl(
        fd: FileDescriptor<*>,
        request: Long,
        arg: MemorySegment,
    ): LinuxNative.SyscallResult<Long, *>
}

/**
 * Interface for socket creation and connection handling.
 */
interface SocketLifecycleManager {
    fun createServer(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket>

    fun accept(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket>): FileDescriptor<FileDescriptorRole.UnixSocket>

    fun close(fd: FileDescriptor<*>)

    fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket>): FileDescriptor<FileDescriptorRole.SeccompNotif>?
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
        socketFd: FileDescriptor<*>,
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
            buf.set(JAVA_INT_BE_UNALIGNED, offset, event.pid)
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
        session: HandshakeSession.Success,
        resp: MemorySegment,
    ) {
        resp.fill(0)
        resp.set(ValueLayout.JAVA_LONG, RESP_ID_OFF, session.notifId)
        resp.set(ValueLayout.JAVA_LONG, RESP_VAL_OFF, 0L)
        resp.set(ValueLayout.JAVA_INT, RESP_ERR_OFF, 0)
        resp.set(ValueLayout.JAVA_INT, RESP_FLAGS_OFF, NativeConstants.SECCOMP_USER_NOTIF_FLAG_CONTINUE.toInt())
        ioctl(session.listenerFd, SECCOMP_IOCTL_NOTIF_SEND, resp)
    }

    override fun sendSeccompError(
        session: HandshakeSession.Failed,
        resp: MemorySegment,
        errorNr: Int,
    ) {
        resp.fill(0)
        resp.set(ValueLayout.JAVA_LONG, RESP_ID_OFF, session.notifId)
        resp.set(ValueLayout.JAVA_LONG, RESP_VAL_OFF, -1L)
        // Error numbers are negative in the 'error' field of seccomp_notif_resp
        resp.set(ValueLayout.JAVA_INT, RESP_ERR_OFF, -errorNr)
        resp.set(ValueLayout.JAVA_INT, RESP_FLAGS_OFF, 0)
        ioctl(session.listenerFd, SECCOMP_IOCTL_NOTIF_SEND, resp)
    }

    override fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket>): FileDescriptor<FileDescriptorRole.SeccompNotif>? {
        return Arena.ofConfined().use { arena ->
            val dummyByte = arena.allocate(ValueLayout.JAVA_BYTE)
            val controlBuf = arena.allocate(24)
            controlBuf.fill(0)

            val msg = with(arena) {
                DescriptorPassing.setupScmRightsMsgHdr(dummyByte, controlBuf)
            }

            while (true) {
                val res = LinuxNative.withTransaction { LinuxNative.networking.recvmsg(socketFd, msg, 0) }
                res.onSuccess { value ->
                    if (value == 0L) return@use null // EOF

                    val cmsgLen = controlBuf.get(ValueLayout.JAVA_LONG, CMSG_LEN_OFF)
                    val cmsgLevel = controlBuf.get(ValueLayout.JAVA_INT, CMSG_LEVEL_OFF)
                    val cmsgType = controlBuf.get(ValueLayout.JAVA_INT, CMSG_TYPE_OFF)
                    if (cmsgLen >= CMSG_LEN_VAL && cmsgLevel == SOL_SOCKET_VAL && cmsgType == SCM_RIGHTS_VAL) {
                        return@use FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(controlBuf.get(ValueLayout.JAVA_INT, CMSG_DATA_OFF))
                    }
                }.onFailure { errno, _ ->
                    if (errno == 4) return@onFailure // EINTR, continue loop
                    return@use null
                }
            }
            null
        }
    }

    override fun poll(
        fds: MemorySegment,
        nfds: Long,
        timeout: Int,
    ): LinuxNative.SyscallResult<Long, *> = LinuxNative.withTransaction { LinuxNative.poll(fds, nfds, timeout) }

    override fun read(
        fd: FileDescriptor<*>,
        buf: MemorySegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long, *> = LinuxNative.withTransaction { LinuxNative.memory.read(fd, buf, count) }

    override fun write(
        fd: FileDescriptor<*>,
        buf: MemorySegment,
        count: Long,
    ): LinuxNative.SyscallResult<Long, *> = LinuxNative.withTransaction { LinuxNative.memory.write(fd, buf, count) }

    override fun recv(
        sockfd: FileDescriptor<*>,
        buf: MemorySegment,
        len: Long,
        flags: Int,
    ): LinuxNative.SyscallResult<Long, *> = LinuxNative.withTransaction { LinuxNative.networking.recv(sockfd, buf, len, flags) }

    override fun ioctl(
        fd: FileDescriptor<*>,
        request: Long,
        arg: MemorySegment,
    ): LinuxNative.SyscallResult<Long, *> = LinuxNative.withTransaction { LinuxNative.ioctl(fd, request, arg) }

    override fun createServer(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket> {
        val fd = LinuxNative.withTransaction {
            LinuxNative.networking.socket(AF_UNIX, SOCK_STREAM, 0)
        }.getFdOrThrow("socket(AF_UNIX)").let { FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(it.value) }

        Arena.ofConfined().use { arena ->
            val addr = arena.allocate(Layouts.SOCKADDR_UN)
            addr.fill(0)
            addr.set(ValueLayout.JAVA_SHORT, 0L, AF_UNIX.toShort())
            val pathBytes = socketPath.toByteArray(StandardCharsets.UTF_8)
            val pathSeg = addr.asSlice(2, SOCKADDR_UN_PATH_SIZE.toLong())
            MemorySegment.copy(pathBytes, 0, pathSeg, ValueLayout.JAVA_BYTE, 0L, pathBytes.size)

            LinuxNative.withTransaction {
                LinuxNative.networking.bind(fd, addr, ADDR_UN_SIZE)
            }.onFailure { _, _ ->
                LinuxNative.fileSystem.close(fd)
            }.getOrThrow("bind(AF_UNIX)")
        }

        LinuxNative.withTransaction {
            LinuxNative.networking.listen(fd, BACKLOG_SIZE)
        }.onFailure { _, _ ->
            LinuxNative.fileSystem.close(fd)
        }.getOrThrow("listen")

        return fd
    }

    override fun accept(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket>): FileDescriptor<FileDescriptorRole.UnixSocket> {
        val res = LinuxNative.withTransaction { LinuxNative.networking.accept(serverFd, MemorySegment.NULL, MemorySegment.NULL) }
        return res.getFdOrThrow("accept").let { FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(it.value) }
    }

    override fun close(fd: FileDescriptor<*>) {
        LinuxNative.fileSystem.close(fd)
    }
}
