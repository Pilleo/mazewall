package io.mazewall.portal

import io.mazewall.LinuxNative
import io.mazewall.core.Deadline
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.RealSocketManager
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.ffi.memory.readByte
import io.mazewall.ffi.memory.writeByte
import io.mazewall.ffi.networking.SupervisorSocketUtils
import java.io.IOException

/** Exclusively owned portal sockets are nonblocking; readiness is advisory, never permission to block. */
internal class PortalIo(
    private val socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    private val closing: () -> Boolean,
) {
    init {
        val flags = LinuxNative.raw.fcntl(socket, F_GETFL, 0).getOrThrow("portal F_GETFL")
        LinuxNative.raw.fcntl(socket, F_SETFL, flags or O_NONBLOCK).getOrThrow("portal O_NONBLOCK")
    }

    fun write(
        bytes: ByteArray,
        deadline: Deadline,
    ) = transfer(bytes, deadline, writing = true, idleAllowed = false)

    fun read(
        size: Int,
        deadline: Deadline,
        idleAllowed: Boolean,
    ): ByteArray = ByteArray(size).also { transfer(it, deadline, writing = false, idleAllowed) }

    private fun transfer(
        bytes: ByteArray,
        deadline: Deadline,
        writing: Boolean,
        idleAllowed: Boolean,
    ) {
        if (bytes.isEmpty()) return
        NativeArena.ofConfined().use { arena ->
            val buffer = arena.allocate(bytes.size.toLong())
            if (writing) bytes.forEachIndexed { index, byte -> buffer.writeByte(index.toLong(), byte) }
            var position = 0
            while (position < bytes.size) {
                val beforeFrame = idleAllowed && position == 0
                await(deadline, if (writing) POLLOUT else NativeConstants.POLLIN, beforeFrame)
                val slice = buffer.asSlice(position.toLong(), (bytes.size - position).toLong())
                val result = if (writing) LinuxNative.memory.write(socket, slice, slice.byteSize()) else LinuxNative.memory.read(socket, slice, slice.byteSize())
                when (result) {
                    is LinuxNative.SyscallResult.Success -> {
                        position += transferProgress(result.value, beforeFrame)
                    }
                    is LinuxNative.SyscallResult.Error -> if (result.errno != NativeConstants.EINTR &&
                        result.errno != NativeConstants.EAGAIN
                    ) {
                            throw IOException("portal transfer errno=${result.errno}")
                        }
                }
            }
            if (!writing) bytes.indices.forEach { bytes[it] = buffer.readByte(it.toLong()) }
        }
    }

    private fun transferProgress(
        count: Long,
        beforeFrame: Boolean,
    ): Int {
        if (count == 0L && beforeFrame) throw PortalPeerClosedException()
        if (count <= 0) throw IOException("portal peer closed during transfer")
        return count.toInt()
    }

    fun sendDescriptor(
        fd: FileDescriptor<*, FdState.Open, FdOwnership>,
        deadline: Deadline,
    ) {
        if (!SupervisorSocketUtils.sendDescriptor(socket.value, fd.value, LinuxNative.networking) { await(deadline, POLLOUT, false) }) throw IOException("portal SCM_RIGHTS send failed")
    }

    @Suppress("TooGenericExceptionCaught") // A received descriptor remains owned even if allocating its wrapper fails.
    fun receiveDescriptor(deadline: Deadline): Capability.ReadFd {
        val fd = SupervisorSocketUtils.recvDescriptor(socket, FileDescriptorRole.Granted) { await(deadline, NativeConstants.POLLIN, false) }
            ?: throw IOException("portal expected granted FD")
        try {
            return Capability.readFd(fd)
        } catch (failure: Throwable) {
            try {
                RealSocketManager.close(fd)
            } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    // Poll readiness, cancellation, deadline classification, and EINTR retry form one transport
    // transition; extracting any branch would obscure whether an idle or partial frame timed out.
    private fun await(
        deadline: Deadline,
        events: Short,
        idleAllowed: Boolean,
    ) {
        NativeArena.ofConfined().use { arena ->
            val pfd = PollFdSegment.of(arena.allocate(Layouts.POLLFD))
            pfd.setFd(socket.value)
            pfd.setEvents(events)
            while (true) {
                if (pollReady(pfd, deadline, idleAllowed)) return
            }
        }
    }

    private fun pollReady(
        pfd: PollFdSegment,
        deadline: Deadline,
        idleAllowed: Boolean,
    ): Boolean {
        if (closing()) failIo("portal channel is closing")
        val remaining = deadline.remainingMillis()
        if (remaining <= 0) timeout(idleAllowed)
        return when (val result = LinuxNative.raw.poll(pfd.managed, 1, minOf(remaining, POLL_SLICE_MS))) {
            is LinuxNative.SyscallResult.Success -> result.value > 0 && validateReady(deadline, idleAllowed)
            is LinuxNative.SyscallResult.Error -> {
                if (result.errno != NativeConstants.EINTR) failIo("portal poll errno=${result.errno}")
                false
            }
        }
    }

    private fun validateReady(
        deadline: Deadline,
        idleAllowed: Boolean,
    ): Boolean {
        if (closing()) failIo("portal channel is closing")
        if (deadline.isExpired()) timeout(idleAllowed)
        return true
    }

    private fun failIo(message: String): Nothing = throw IOException(message)

    private fun timeout(idleAllowed: Boolean): Nothing = if (idleAllowed) throw PortalReadTimeoutException() else throw PortalIncompleteFrameException()

    private companion object {
        const val F_GETFL = 3
        const val F_SETFL = 4
        const val O_NONBLOCK = 0x800L
        const val POLLOUT: Short = 4
        const val POLL_SLICE_MS = 50
    }
}
