package io.mazewall.platform.seccomp.daemon

import io.mazewall.LinuxNative
import io.mazewall.MazewallInternal
import io.mazewall.NativeMemory
import io.mazewall.RawSyscallOperations
import io.mazewall.core.Deadline
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena

@MazewallInternal
public class ControlSocketFailure(
    public val errno: Int,
) : java.io.IOException("Control socket exchange failed: errno=$errno")

/**
 * One bounded control frame exchange on a daemon-owned O_NONBLOCK stream socket.
 * A poll performs at most one write/read; short transfers, EAGAIN and EINTR yield to
 * the reactor without renewing the notification deadline. Only heap bytes survive a poll.
 */
@MazewallInternal
public class ControlSocketExchange(
    raw: RawSyscallOperations,
    private val memory: NativeMemory,
    private val socket: FileDescriptor<*, FdState.Open, FdOwnership>,
    outbound: ByteArray,
    responseSize: Int,
    private val deadline: Deadline,
    private val minimumResponseSize: Int = responseSize,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val request = outbound.copyOf()
    private val response = ByteArray(responseSize)
    private var sent = 0
    private var received = 0
    private var interruptions = 0

    public val requestSent: Boolean get() = sent == request.size

    init {
        require(responseSize >= 0)
        require(minimumResponseSize in 0..responseSize)
        // This is the daemon's private control endpoint, never a tracee open-file description.
        val flags = raw.fcntl(socket, F_GETFL, 0)
        when (flags) {
            is LinuxNative.SyscallResult.Error -> throw ControlSocketFailure(flags.errno)
            is LinuxNative.SyscallResult.Success -> when (val configured = raw.fcntl(socket, F_SETFL, flags.value or O_NONBLOCK)) {
                is LinuxNative.SyscallResult.Error -> throw ControlSocketFailure(configured.errno)
                is LinuxNative.SyscallResult.Success -> Unit
            }
        }
    }

    /** Null means Await. Completion is returned only after the full request and response. */
    context(arena: NativeArena) public fun poll(): ByteArray? {
        if (nanoTime() - deadline.nanoTime >= 0) throw ControlSocketFailure(NativeConstants.ETIMEDOUT)
        if (sent < request.size) {
            val buffer = arena.allocate((request.size - sent).toLong())
            ManagedSegment.copy(request, sent, buffer, 0, request.size - sent)
            val moved = transfer(memory.write(socket, buffer, buffer.byteSize()), request.size - sent) ?: return null
            sent += moved
            return if (sent == request.size && response.isEmpty()) response.copyOf() else null
        }
        if (received < response.size) {
            val buffer = arena.allocate((response.size - received).toLong())
            val moved = transfer(memory.read(socket, buffer, buffer.byteSize()), response.size - received) ?: return null
            ManagedSegment.copy(buffer, 0, response, received, moved)
            received += moved
        }
        return if (received >= minimumResponseSize) response.copyOf(received) else null
    }

    private fun transfer(
        result: LinuxNative.SyscallResult<Long, *>,
        remaining: Int,
    ): Int? =
        when (result) {
        is LinuxNative.SyscallResult.Error -> when (result.errno) {
            NativeConstants.EAGAIN -> null
            NativeConstants.EINTR -> {
                interruptions++
                if (interruptions >= MAX_INTERRUPTS) throw ControlSocketFailure(result.errno)
                null
            }
            else -> throw ControlSocketFailure(result.errno)
        }
        is LinuxNative.SyscallResult.Success -> {
            if (result.value <= 0 || result.value > remaining) throw ControlSocketFailure(NativeConstants.EIO)
            result.value.toInt()
        }
    }

    private companion object {
        const val F_GETFL = 3
        const val F_SETFL = 4
        const val O_NONBLOCK = 0x800L
        const val MAX_INTERRUPTS = 8
    }
}
