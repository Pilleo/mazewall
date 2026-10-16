package io.mazewall.platform.seccomp.daemon

import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.lifecycle.Deadline
import io.mazewall.platform.lifecycle.MazewallInternal
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeMemory
import io.mazewall.platform.native.RawSyscallOperations

@MazewallInternal
internal class ControlSocketFailure(
    public val errno: Int,
) : java.io.IOException("Control socket exchange failed: errno=$errno")

/**
 * One bounded control frame exchange on a daemon-owned O_NONBLOCK stream socket.
 * A poll performs at most one write/read; short transfers, EAGAIN and EINTR yield to
 * the reactor without renewing the notification deadline. Only heap bytes survive a poll.
 */
@MazewallInternal
internal class ControlSocketExchange(
    private val raw: RawSyscallOperations,
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

    public val requestSent: Boolean get() = sent == request.size

    init {
        require(responseSize >= 0)
        require(minimumResponseSize in 0..responseSize)
        // This is the daemon's private control endpoint, never a tracee open-file description.
        val flags = raw.fcntl(socket, NativeConstants.F_GETFL, 0)
        when (flags) {
            is LinuxNative.SyscallResult.Error -> throw ControlSocketFailure(flags.errno)
            is LinuxNative.SyscallResult.Success -> when (val configured = raw.fcntl(socket, NativeConstants.F_SETFL, flags.value or NativeConstants.O_NONBLOCK)) {
                is LinuxNative.SyscallResult.Error -> throw ControlSocketFailure(configured.errno)
                is LinuxNative.SyscallResult.Success -> Unit
            }
        }
    }

    /** SUPPRESSION-RATIONALE: Null means Await; each partial transfer yields immediately to preserve one I/O attempt per poll. */
    context(arena: NativeArena) public fun poll(): ByteArray? {
        if (nanoTime() - deadline.nanoTime >= 0) throw ControlSocketFailure(NativeConstants.ETIMEDOUT)
        val event = if (sent < request.size) NativeConstants.POLLOUT else NativeConstants.POLLIN
        if (!ready(event)) return null
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

    context(arena: NativeArena) private fun ready(event: Short): Boolean {
        val pollFd = PollFdSegment.of(arena.allocate(io.mazewall.ffi.Layouts.POLLFD))
        pollFd.setFd(socket.value)
        pollFd.setEvents(event)
        pollFd.setRevents(0)
        return when (val result = raw.poll(pollFd.managed, 1, 0)) {
            is LinuxNative.SyscallResult.Error -> when (result.errno) {
                NativeConstants.EINTR, NativeConstants.EAGAIN -> false
                else -> throw ControlSocketFailure(result.errno)
            }
            is LinuxNative.SyscallResult.Success -> {
                val revents = pollFd.getRevents().toInt().let { value ->
                    if (value == 0 && result.value > 0L) event.toInt() else value
                }
                val terminal = NativeConstants.POLLERR.toInt() or NativeConstants.POLLHUP.toInt() or NativeConstants.POLLNVAL.toInt()
                if ((revents and terminal) != 0) throw ControlSocketFailure(NativeConstants.EIO)
                result.value > 0 && (revents and event.toInt()) != 0
            }
        }
    }

    private fun transfer(
        result: LinuxNative.SyscallResult<Long, *>,
        remaining: Int,
    ): Int? =
        when (result) {
        is LinuxNative.SyscallResult.Error -> when (result.errno) {
            NativeConstants.EAGAIN, NativeConstants.EINTR -> null
            else -> throw ControlSocketFailure(result.errno)
        }
        is LinuxNative.SyscallResult.Success -> {
            if (result.value <= 0 || result.value > remaining) throw ControlSocketFailure(NativeConstants.EIO)
            result.value.toInt()
        }
    }
}
