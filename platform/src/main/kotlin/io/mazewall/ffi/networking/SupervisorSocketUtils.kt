package io.mazewall.ffi.networking

import io.mazewall.LinuxNative
import io.mazewall.NativeNetworking
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.CmsghdrSegment
import io.mazewall.ffi.memory.ConfinedSegment
import io.mazewall.ffi.memory.IovecSegment
import io.mazewall.ffi.memory.MsghdrSegment
import io.mazewall.ffi.memory.SockaddrUnSegment
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

    // LP64: CMSG_LEN(sizeof(int)) = CMSG_ALIGN(sizeof(cmsghdr)) + sizeof(int) = 16 + 4
    public const val CMSG_RIGHTS_LEN: Long = 20L

    // LP64: CMSG_SPACE(sizeof(int)) = CMSG_ALIGN(sizeof(cmsghdr)) + CMSG_ALIGN(sizeof(int)) = 16 + 8
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
        delayMs: Long = 10L,
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
                val fd = FileDescriptor.adopt(fdVal, FileDescriptorRole.UnixSocket)
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
                "Failed to connect to socket at $socketPath after $maxRetries retries. Last errno=$lastErrno",
            )
        }
    }

    public fun sendDescriptor(
        socketFd: Int,
        fdToSend: Int,
    ): Boolean = sendDescriptor(socketFd, fdToSend, LinuxNative.snapshot().networking)

    /** Descriptor transfer using the caller's captured native transport. */
    @io.mazewall.MazewallInternal
    public fun sendDescriptor(
        socketFd: Int,
        fdToSend: Int,
        networking: NativeNetworking,
        beforeAttempt: (() -> Unit)? = null,
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
                beforeAttempt?.invoke()
                val res = networking.sendmsg(FileDescriptor.unixSocket(socketFd), ConfinedSegment(msg.segment), if (beforeAttempt == null) 0 else 0x40)
                if (res is LinuxNative.SyscallResult.Success) {
                    return res.value == 1L
                } else {
                    val errno = (res as LinuxNative.SyscallResult.Error).errno
                    if (errno == io.mazewall.ffi.NativeConstants.EINTR) continue
                    if (beforeAttempt != null && errno == NativeConstants.EAGAIN) continue
                    return false
                }
            }
        }
    }

    public fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership>): FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>? =
        recvDescriptor(socketFd, FileDescriptorRole.SeccompNotif)

    public fun <R : FileDescriptorRole> recvDescriptor(
        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership>,
        role: R,
        beforeAttempt: (() -> Unit)? = null,
    ): FileDescriptor<R, FdState.Open, FdOwnership.Owned>? {
        val runtime = LinuxNative.snapshot()
        Arena.ofConfined().use { arena ->
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

            while (true) {
                beforeAttempt?.invoke()
                // recvmsg mutates these in/out fields; restore capacity for EINTR retries.
                controlBuf.fill(0)
                msg.setMsgControllen(MSG_CONTROL_BUF_SIZE)
                msg.setMsgFlags(0)
                val res = runtime.networking.recvmsg(socketFd, ConfinedSegment(msg.segment), NativeConstants.MSG_CMSG_CLOEXEC or if (beforeAttempt == null) 0 else 0x40)
                if (res is LinuxNative.SyscallResult.Success) {
                    return validateReceivedDescriptor(
                        bytesReceived = res.value,
                        message = msg,
                        control = controlBuf,
                        role = role,
                        fileSystem = runtime.fileSystem,
                    )
                } else {
                    val errno = (res as LinuxNative.SyscallResult.Error).errno
                    if (errno == io.mazewall.ffi.NativeConstants.EINTR) continue // EINTR
                    if (beforeAttempt != null && errno == NativeConstants.EAGAIN) continue
                    return null
                }
            }
        }
    }

    // Receipt validation owns every integer decoded from SCM_RIGHTS before any rejection path.
    private fun <R : FileDescriptorRole> validateReceivedDescriptor(
        bytesReceived: Long,
        message: MsghdrSegment,
        control: MemorySegment,
        role: R,
        fileSystem: io.mazewall.NativeFileSystem,
    ): FileDescriptor<R, FdState.Open, FdOwnership.Owned>? {
        val cmsg = CmsghdrSegment(control)
        val controlLength = message.getMsgControllen()
        val descriptors = decodeDescriptors(control, controlLength, cmsg)
        val truncated = message.getMsgFlags() and (NativeConstants.MSG_CTRUNC or NativeConstants.MSG_TRUNC) != 0
        val valid = bytesReceived == 1L &&
            !truncated &&
            controlLength in CMSG_RIGHTS_LEN..MSG_CONTROL_BUF_SIZE &&
            cmsg.getCmsgLen() == CMSG_RIGHTS_LEN &&
            descriptors.size == 1
        if (valid) return FileDescriptor.adopt(descriptors.single(), role)
        closeRejectedDescriptors(descriptors, role, fileSystem)
        return null
    }

    private fun decodeDescriptors(
        control: MemorySegment,
        controlLength: Long,
        cmsg: CmsghdrSegment,
    ): List<Int> {
        val carriesRights = controlLength >= Layouts.CMSGHDR_DATA_OFFSET &&
            cmsg.getCmsgLevel() == SOL_SOCKET &&
            cmsg.getCmsgType() == SCM_RIGHTS
        val dataEnd = minOf(controlLength, cmsg.getCmsgLen(), MSG_CONTROL_BUF_SIZE)
        if (!carriesRights || dataEnd < CMSG_RIGHTS_LEN) return emptyList()
        return List(((dataEnd - Layouts.CMSGHDR_DATA_OFFSET) / Int.SIZE_BYTES).toInt()) { index ->
            control.get(ValueLayout.JAVA_INT, Layouts.CMSGHDR_DATA_OFFSET + index * Int.SIZE_BYTES)
        }
    }

    // Independent received FDs must be closed before fatal/cancellation failures
    // propagate. Broad capture here never converts failure to success.
    @Suppress("TooGenericExceptionCaught")
    private fun closeRejectedDescriptors(
        descriptors: List<Int>,
        role: FileDescriptorRole,
        fileSystem: io.mazewall.NativeFileSystem,
    ) {
        var cleanupFailure: Throwable? = null
        descriptors.forEach { received ->
            try {
                fileSystem.close(FileDescriptor.adopt(received, role)).getOrThrow("close rejected SCM_RIGHTS descriptor")
            } catch (fatal: Error) {
                val previous = cleanupFailure
                if (previous == null) {
                    cleanupFailure = fatal
                } else if (previous !is Error) {
                    // Preserve fatal identity for callers that distinguish JVM
                    // failures from ordinary transport/session cleanup exceptions.
                    fatal.addSuppressed(previous)
                    cleanupFailure = fatal
                } else if (previous !== fatal) {
                    previous.addSuppressed(fatal)
                }
            } catch (failure: Throwable) {
                val previous = cleanupFailure
                if (previous == null) {
                    cleanupFailure = failure
                } else if (previous !== failure) {
                    previous.addSuppressed(failure)
                }
            }
        }
        cleanupFailure?.let { throw it }
    }
}
