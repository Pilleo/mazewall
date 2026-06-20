package io.mazewall.ffi.networking

import io.mazewall.LinuxNative
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.memory.CmsghdrSegment
import io.mazewall.ffi.memory.IovecSegment
import io.mazewall.ffi.memory.MsghdrSegment
import io.mazewall.ffi.memory.SockaddrUnSegment
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.charset.StandardCharsets

/**
 * Represents the lifecycle of a connection between a seccomp daemon and the tracee JVM.
 */
public sealed class SeccompConnection {
    public abstract val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>

    /** Initial state: Connection accepted, waiting to receive the seccomp listener FD. */
    public data class Accepted(
        override val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
    ) : SeccompConnection() {
        public fun attachFd(listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>): FdAttached =
            FdAttached(socketFd, listenerFd)
    }

    /** Intermediate state: Listener FD received, waiting to send the 0xAC ACK byte. */
    public data class FdAttached(
        override val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
    ) : SeccompConnection() {
        public fun handshakeComplete(): Active = Active(socketFd, listenerFd)
    }

    /** Established state: Handshake complete, session is now active and polling. */
    public data class Active(
        override val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        val listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>,
    ) : SeccompConnection()
}

/**
 * Shared utilities for SCM_RIGHTS descriptor passing and AF_UNIX socket connections.
 */
public object SupervisorSocketUtils {
    public const val AF_UNIX: Int = 1
    public const val SOCK_STREAM: Int = 1
    public const val BACKLOG_SIZE: Int = 128
    public const val SOCKADDR_UN_SIZE: Int = 110
    public const val CMSG_RIGHTS_LEN: Long = 20L
    public const val MSG_CONTROL_BUF_SIZE: Long = 24L
    public const val SOL_SOCKET: Int = 1
    public const val SCM_RIGHTS: Int = 1

    public fun setupSockAddrUn(
        arena: Arena,
        socketPath: String,
    ): SockaddrUnSegment {
        val sockaddrUn = SockaddrUnSegment(arena.allocate(Layouts.SOCKADDR_UN))
        sockaddrUn.segment.fill(0)
        sockaddrUn.setSunFamily(AF_UNIX.toShort())
        val pathBytes = socketPath.toByteArray(StandardCharsets.UTF_8)
        val pathSeg = sockaddrUn.getSunPath()
        MemorySegment.copy(pathBytes, 0, pathSeg, ValueLayout.JAVA_BYTE, 0L, pathBytes.size)
        return sockaddrUn
    }

    public fun connectWithRetry(
        socketPath: String,
        maxRetries: Int = 500,
        delayMs: Long = 10L
    ): Int {
        Arena.ofConfined().use { arena ->
            val sockaddrUn = setupSockAddrUn(arena, socketPath)

            var lastErrno = 0
            for (retry in 0 until maxRetries) {
                val fdRes = LinuxNative.withTransaction {
                    LinuxNative.networking.socket(AF_UNIX, SOCK_STREAM, 0)
                }
                val fdVal = when (fdRes) {
                    is LinuxNative.SyscallResult.Success -> fdRes.value.toInt()
                    is LinuxNative.SyscallResult.Error -> {
                        lastErrno = fdRes.errno
                        Thread.sleep(delayMs)
                        continue
                    }
                }
                val fd = FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(fdVal)
                val connRes = LinuxNative.withTransaction {
                    LinuxNative.networking.connect(fd, sockaddrUn.segment, SOCKADDR_UN_SIZE)
                }
                if (connRes is LinuxNative.SyscallResult.Success) {
                    return fdVal
                }
                lastErrno = (connRes as LinuxNative.SyscallResult.Error).errno
                LinuxNative.fileSystem.close(fd)

                Thread.sleep(delayMs)
            }
            throw IllegalStateException(
                "Failed to connect to socket at $socketPath after $maxRetries retries. Last errno=$lastErrno"
            )
        }
    }

    public fun sendDescriptor(
        socketFd: Int,
        fdToSend: Int
    ): Boolean {
        Arena.ofConfined().use { arena ->
            val dummyByte = arena.allocate(ValueLayout.JAVA_BYTE)
            dummyByte.set(ValueLayout.JAVA_BYTE, 0L, 0.toByte())

            val controlBuf = arena.allocate(MSG_CONTROL_BUF_SIZE)
            controlBuf.fill(0)
            val cmsg = CmsghdrSegment(controlBuf)
            cmsg.setCmsgLen(CMSG_RIGHTS_LEN)
            cmsg.setCmsgLevel(SOL_SOCKET)
            cmsg.setCmsgType(SCM_RIGHTS)
            cmsg.setDataFd(fdToSend)

            val iov = IovecSegment(arena.allocate(Layouts.IOVEC))
            iov.setIovBase(dummyByte)
            iov.setIovLen(1L)

            val msg = MsghdrSegment(arena.allocate(Layouts.MSGHDR))
            msg.setMsgIov(iov.segment)
            msg.setMsgIovlen(1L)
            msg.setMsgControl(controlBuf)
            msg.setMsgControllen(MSG_CONTROL_BUF_SIZE)

            val res = LinuxNative.withTransaction {
                LinuxNative.networking.sendmsg(FileDescriptor.unsafe<FileDescriptorRole.UnixSocket>(socketFd), msg.segment, 0)
            }
            return res is LinuxNative.SyscallResult.Success
        }
    }

    public fun recvDescriptor(
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>
    ): FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open>? {
        return Arena.ofConfined().use { arena ->
            val dummyByte = arena.allocate(ValueLayout.JAVA_BYTE)
            val controlBuf = arena.allocate(MSG_CONTROL_BUF_SIZE)
            controlBuf.fill(0)

            val iov = IovecSegment(arena.allocate(Layouts.IOVEC))
            iov.setIovBase(dummyByte)
            iov.setIovLen(1L)

            val msg = MsghdrSegment(arena.allocate(Layouts.MSGHDR))
            msg.setMsgIov(iov.segment)
            msg.setMsgIovlen(1L)
            msg.setMsgControl(controlBuf)
            msg.setMsgControllen(MSG_CONTROL_BUF_SIZE)

            val cmsg = CmsghdrSegment(controlBuf)

            while (true) {
                val res = LinuxNative.withTransaction { LinuxNative.networking.recvmsg(socketFd, msg.segment, 0) }
                if (res is LinuxNative.SyscallResult.Success) {
                    val value = res.value
                    if (value == 0L) return@use null

                    val cmsgLen = cmsg.getCmsgLen()
                    val cmsgLevel = cmsg.getCmsgLevel()
                    val cmsgType = cmsg.getCmsgType()
                    if (cmsgLen >= CMSG_RIGHTS_LEN && cmsgLevel == SOL_SOCKET && cmsgType == SCM_RIGHTS) {
                        return@use FileDescriptor.unsafe<FileDescriptorRole.SeccompNotif>(cmsg.getDataFd())
                    }
                } else {
                    val errno = (res as LinuxNative.SyscallResult.Error).errno
                    if (errno == io.mazewall.ffi.NativeConstants.EINTR) continue // EINTR
                    return@use null
                }
            }
            null
        }
    }
}
