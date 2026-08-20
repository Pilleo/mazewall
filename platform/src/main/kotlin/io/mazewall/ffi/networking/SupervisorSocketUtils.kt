package io.mazewall.ffi.networking

import io.mazewall.LinuxNative
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.CmsghdrSegment
import io.mazewall.ffi.memory.IovecSegment
import io.mazewall.ffi.memory.MsghdrSegment
import io.mazewall.ffi.memory.SockaddrUnSegment
import io.mazewall.ffi.memory.ConfinedSegment
import io.mazewall.ffi.memory.unwrap
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.charset.StandardCharsets

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
        arena: io.mazewall.ffi.memory.NativeArena,
        socketPath: String,
    ): SockaddrUnSegment {
        val sockaddrUn = SockaddrUnSegment(arena.allocate(Layouts.SOCKADDR_UN).unwrap)
        sockaddrUn.segment.fill(0)
        sockaddrUn.setSunFamily(AF_UNIX.toShort())
        val pathBytes = socketPath.toByteArray(StandardCharsets.UTF_8)
        // Strict bounds check to prevent native buffer overflow / OutOfBoundsException on long paths
        require(pathBytes.size < 108) {
            "Socket path too long: $socketPath (length: ${pathBytes.size}, max: 107)"
        }
        val pathSeg = sockaddrUn.getSunPath()
        MemorySegment.copy(pathBytes, 0, pathSeg, ValueLayout.JAVA_BYTE, 0L, pathBytes.size)
        return sockaddrUn
    }

    public fun connectWithRetry(
        socketPath: String,
        maxRetries: Int = 500,
        delayMs: Long = 10L
    ): Int {
        io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
            val sockaddrUn = setupSockAddrUn(arena, socketPath)

            var lastErrno = 0
            for (retry in 0 until maxRetries) {
                val fdRes = LinuxNative.networking.socket(AF_UNIX, SOCK_STREAM or NativeConstants.SOCK_CLOEXEC, 0)
                val fdVal = when (fdRes) {
                    is LinuxNative.SyscallResult.Success -> fdRes.value.toInt()
                    is LinuxNative.SyscallResult.Error -> {
                        lastErrno = fdRes.errno
                        try {
                            Thread.sleep(delayMs)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw IllegalStateException("Interrupted while waiting to retry socket creation for $socketPath", e)
                        }
                        continue
                    }
                }
                val fd = FileDescriptor.unixSocket(fdVal)
                val connRes = LinuxNative.networking.connect(fd, ConfinedSegment(sockaddrUn.segment), SOCKADDR_UN_SIZE)
                if (connRes is LinuxNative.SyscallResult.Success) {
                    return fdVal
                }
                lastErrno = (connRes as LinuxNative.SyscallResult.Error).errno
                LinuxNative.fileSystem.close(fd)

                try {
                    Thread.sleep(delayMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IllegalStateException("Interrupted while waiting to retry socket connection for $socketPath", e)
                }
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
            msg.setMsgControllen(CMSG_RIGHTS_LEN)

            while (true) {
                val res = LinuxNative.networking.sendmsg(FileDescriptor.unixSocket(socketFd), ConfinedSegment(msg.segment), 0)
                if (res is LinuxNative.SyscallResult.Success) {
                    return true
                } else {
                    val errno = (res as LinuxNative.SyscallResult.Error).errno
                    if (errno == io.mazewall.ffi.NativeConstants.EINTR) continue
                    return false
                }
            }
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
                val res = LinuxNative.networking.recvmsg(socketFd, ConfinedSegment(msg.segment), 0)
                if (res is LinuxNative.SyscallResult.Success) {
                    val value = res.value
                    if (value == 0L) return@use null

                    val cmsgLen = cmsg.getCmsgLen()
                    val cmsgLevel = cmsg.getCmsgLevel()
                    val cmsgType = cmsg.getCmsgType()
                    if (cmsgLen >= CMSG_RIGHTS_LEN && cmsgLevel == SOL_SOCKET && cmsgType == SCM_RIGHTS) {
                        return@use FileDescriptor.adopt<FileDescriptorRole.SeccompNotif>(cmsg.getDataFd())
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
