package io.mazewall.portal

import io.mazewall.LinuxNative
import io.mazewall.core.Deadline
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.OpenFlags
import io.mazewall.core.RealSocketManager
import io.mazewall.core.SocketIo
import io.mazewall.core.SocketManager
import io.mazewall.core.SocketPoll
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.ffi.memory.readByte
import io.mazewall.ffi.memory.writeByte
import io.mazewall.ffi.memory.writeLong
import io.mazewall.getFdOrThrow
import java.nio.file.Path

/** Thrown when a portal read deadline expires. Distinct from fatal socket errors so idle
 *  workers can keep polling (issue: pooled-worker 30s self-exit). */
public class PortalReadTimeoutException :
    java.io.IOException("portal read timed out")

public class PortalChannel(
    private val socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
    private val sockets: SocketManager = RealSocketManager,
) : AutoCloseable {
    fun send(frame: PortalFrame, fds: List<FileDescriptor<*, FdState.Open>> = emptyList()) {
        require(fds.size == frame.fdCount) { "fd list ${fds.size} != header ${frame.fdCount}" }
        writeBytes(frame.headerBytes())
        if (frame.payload.isNotEmpty()) {
            writeBytes(frame.payload)
        }
        for (fd in fds) {
            check(sockets.sendDescriptor(socket, fd)) { "SCM_RIGHTS send failed" }
        }
    }

    fun receive(timeoutMs: Long = 30_000L): Pair<PortalFrame, List<FileDescriptor<FileDescriptorRole.Granted, FdState.Open>>> {
        val headerBytes = readBytes(PortalFrame.HEADER_SIZE, timeoutMs)
        val header = PortalFrame.parseHeader(headerBytes)
        val payload = if (header.payloadLen == 0) ByteArray(0) else readBytes(header.payloadLen, timeoutMs)
        val fds = ArrayList<FileDescriptor<FileDescriptorRole.Granted, FdState.Open>>(header.fdCount)
        repeat(header.fdCount) {
            val granted =
                sockets.recvDescriptor(socket, FileDescriptorRole.Granted)
                    ?: error("expected granted FD")
            fds.add(granted)
        }
        return PortalFrame(header.kind, header.requestId, header.methodId, payload, header.fdCount) to fds
    }

    override fun close() {
        sockets.close(socket)
    }

    private fun writeBytes(bytes: ByteArray) {
        NativeArena.ofConfined().use { arena ->
            val buf = arena.allocate(bytes.size.toLong())
            bytes.forEachIndexed { i, b -> buf.writeByte(i.toLong(), b) }
            when (val res = SocketIo.writeFully(LinuxNative.memory, socket, buf, bytes.size.toLong())) {
                is LinuxNative.SyscallResult.Error<*> -> error("portal write errno=${res.errno}")
                is LinuxNative.SyscallResult.Success -> check(res.value == bytes.size.toLong())
            }
        }
    }

    private fun readBytes(len: Int, timeoutMs: Long): ByteArray {
        NativeArena.ofConfined().use { arena ->
            val buf = arena.allocate(len.toLong())
            val deadline = Deadline.afterMillis(timeoutMs)
            val poll = SocketPoll { timeout -> pollReadable(timeout) }
            when (val res = SocketIo.readFully(LinuxNative.memory, socket, buf, len.toLong(), deadline, poll)) {
                is LinuxNative.SyscallResult.Error<*> -> {
                    if (res.errno == NativeConstants.ETIMEDOUT) throw PortalReadTimeoutException()
                    error("portal read errno=${res.errno}")
                }
                is LinuxNative.SyscallResult.Success -> check(res.value == len.toLong())
            }
            val out = ByteArray(len)
            for (i in 0 until len) {
                out[i] = buf.readByte(i.toLong())
            }
            return out
        }
    }

    private fun pollReadable(timeoutMs: Int): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
        NativeArena.ofConfined().use { arena ->
            val pfd = PollFdSegment.of(arena.allocate(Layouts.POLLFD))
            pfd.setFd(socket.value)
            pfd.setEvents(NativeConstants.POLLIN)
            return LinuxNative.raw.poll(pfd.managed, 1, timeoutMs)
        }
    }
}

internal fun openGrantedRead(rootDir: Path, relative: String): Capability.ReadFd {
    require(relative.isNotEmpty()) { "relative path required" }
    require(!relative.startsWith("/")) { "absolute paths are not granted; pass a path relative to root" }
    require('\u0000' !in relative) { "NUL in path" }

    NativeArena.ofConfined().use { arena ->
        val rootFlags =
            OpenFlags(
                NativeConstants.O_RDONLY or NativeConstants.O_DIRECTORY or NativeConstants.O_CLOEXEC,
            )
        val rootFd =
            LinuxNative.fileSystem
                .open(arena.allocateFrom(rootDir.toAbsolutePath().toString()), rootFlags)
                .getFdOrThrow("open grant root")
        try {
            val how = arena.allocate(Layouts.OPEN_HOW_SIZE)
            how.writeLong(Layouts.OPEN_HOW_FLAGS_OFFSET, (NativeConstants.O_RDONLY or NativeConstants.O_CLOEXEC).toLong())
            how.writeLong(Layouts.OPEN_HOW_MODE_OFFSET, 0L)
            how.writeLong(Layouts.OPEN_HOW_RESOLVE_OFFSET, NativeConstants.RESOLVE_BENEATH)
            when (
                val opened =
                    LinuxNative.fileSystem.openat2(
                        rootFd,
                        arena.allocateFrom(relative),
                        how,
                        Layouts.OPEN_HOW_SIZE,
                    )
            ) {
                is LinuxNative.SyscallResult.Error -> {
                    check(opened.errno != NativeConstants.ENOSYS) {
                        "openat2 is required for granted FDs (fail closed)"
                    }
                    opened.throwErrno("openat2 RESOLVE_BENEATH $relative")
                }

                is LinuxNative.SyscallResult.Success -> {
                    val granted = FileDescriptor.adopt(opened.value.toInt(), FileDescriptorRole.Granted)
                    return Capability.ReadFd(granted)
                }
            }
        } finally {
            LinuxNative.fileSystem.close(rootFd)
        }
    }
}
