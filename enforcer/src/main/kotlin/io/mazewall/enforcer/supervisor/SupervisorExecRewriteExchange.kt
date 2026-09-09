package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.NativeEngine
import io.mazewall.core.Deadline
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.SocketIo
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.ffi.memory.native
import io.mazewall.ffi.memory.readByte
import io.mazewall.ffi.networking.NetworkOrderBuffer

/** Preserves the parent-side 48-byte rewrite request and one-byte acknowledgement protocol. */
internal class SupervisorExecRewriteExchange(
    private val engine: NativeEngine,
    private val socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
) {
    context(arena: NativeArena) fun request(
        exec: SecureExecRequest,
        descriptor: Int,
        emptyPath: Long,
    ): Boolean {
        val buffer = arena.allocate(REWRITE_SIZE)
        val network = NetworkOrderBuffer(buffer.native)
        network.writeLong(0, descriptor.toLong())
        network.writeLong(EMPTY_PATH_OFFSET, emptyPath)
        network.writeLong(ARGV_OFFSET, if (descriptor < 0) 0 else exec.argv)
        network.writeLong(ENVP_OFFSET, if (descriptor < 0) 0 else exec.envp)
        network.writeLong(EXECVEAT_OFFSET, if (descriptor < 0) 0 else exec.execveat)
        network.writeLong(TID_OFFSET, exec.tid.value.toLong())
        if (SocketIo.writeFully(engine.memory, socket, buffer, REWRITE_SIZE) is LinuxNative.SyscallResult.Error) return false
        val ack = arena.allocate(1)
        val poll = PollFdSegment.of(arena.allocate(Layouts.POLLFD))
        poll.setFd(socket.value)
        poll.setEvents(NativeConstants.POLLIN)
        val pollBuffer = poll.managed
        val read = SocketIo.readFully(engine.memory, socket, ack, 1, Deadline.afterMillis(ACK_TIMEOUT_MILLIS)) {
            engine.raw.poll(pollBuffer, 1, it)
        }
        return read is LinuxNative.SyscallResult.Success && ack.readByte(0) == 1.toByte()
    }

    private companion object {
        const val EMPTY_PATH_OFFSET = 8L
        const val ARGV_OFFSET = 16L
        const val ENVP_OFFSET = 24L
        const val EXECVEAT_OFFSET = 32L
        const val TID_OFFSET = 40L
        const val REWRITE_SIZE = 48L
        const val ACK_TIMEOUT_MILLIS = 30_000L
    }
}
