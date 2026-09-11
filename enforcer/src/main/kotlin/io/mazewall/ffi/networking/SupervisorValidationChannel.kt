package io.mazewall.ffi.networking

import io.mazewall.LinuxNative
import io.mazewall.core.Deadline
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.enforcer.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.state.*
import io.mazewall.enforcer.supervisor.JvmVerdict
import io.mazewall.enforcer.supervisor.SupervisorNotificationMachine
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.ffi.memory.SupervisorResponseSegment
import io.mazewall.ffi.memory.readByte
import io.mazewall.ffi.memory.writeByte
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

/**
 * Encapsulates the FFM/Arena details and response structures for the Supervisor JVM Validation process.
 * This class exposes a high-level safe interface to the enforcer packages, hiding MemorySegments and Arenas.
 */
public class SupervisorValidationChannel(
    private val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
) : AutoCloseable {
    private val arena = NativeArena.ofShared()
    private val stopping = java.util.concurrent.atomic
        .AtomicBoolean(false)
    private val native = LinuxNative.snapshot()

    init {
        configureSocketNonblocking()
    }

    private val singleByteInput = arena.allocate(1)
    private val bulkInput = arena.allocate(INPUT_BUFFER_SIZE.toLong())
    private val pollFd = PollFdSegment.of(arena.allocate(Layouts.POLLFD)).apply {
        setFd(socketFd.value)
        setEvents(NativeConstants.POLLIN)
    }
    public val inputStream: InputStream = object : InputStream() {
        override fun read(): Int {
            return readBounded(singleByteInput, 1) { singleByteInput.readByte(0).toInt() and 0xff }
        }

        override fun read(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            java.util.Objects.checkFromIndexSize(offset, length, bytes.size)
            if (length == 0) return 0
            val count = minOf(length, INPUT_BUFFER_SIZE).toLong()
            return readBounded(bulkInput, count) { read ->
                io.mazewall.ffi.memory.ManagedSegment
                    .copy(bulkInput, 0, bytes, offset, read.toInt())
                read.toInt()
            }
        }
    }
    private val responseSegment = with(arena) { SupervisorResponseSegment.allocate() }

    /** Requests reactor-owned cleanup without closing an arena under an active native read. */
    internal fun requestStop() {
        stopping.set(true)
    }

    private fun readBounded(
        buffer: io.mazewall.ffi.memory.ManagedSegment,
        count: Long,
        decode: (Long) -> Int,
    ): Int {
        val deadline = Deadline.afterMillis(configuredTimeoutMillis())
        while (true) {
            awaitReady(NativeConstants.POLLIN, deadline)
            when (val result = native.memory.read(socketFd, buffer, count)) {
                is LinuxNative.SyscallResult.Success -> {
                    if (result.value < 0 || result.value > count) throw IOException("invalid validation read ${result.value}")
                    return if (result.value == 0L) -1 else decode(result.value)
                }
                is LinuxNative.SyscallResult.Error -> when (result.errno) {
                    NativeConstants.EINTR, NativeConstants.EAGAIN -> Unit
                    else -> throw IOException("validation read errno=${result.errno}")
                }
            }
        }
    }

    private fun configureSocketNonblocking() {
        val flags = when (val result = native.raw.fcntl(socketFd, NativeConstants.F_GETFL, 0)) {
            is LinuxNative.SyscallResult.Success -> result.value
            is LinuxNative.SyscallResult.Error -> throw java.io.IOException("validation fcntl F_GETFL errno=${result.errno}")
        }
        when (val result = native.raw.fcntl(socketFd, NativeConstants.F_SETFL, flags or NativeConstants.O_NONBLOCK)) {
            is LinuxNative.SyscallResult.Success -> Unit
            is LinuxNative.SyscallResult.Error -> throw java.io.IOException("validation fcntl F_SETFL errno=${result.errno}")
        }
    }

    public fun sendResponse(
        id: Long,
        decision: Byte,
        errorNr: Int,
        path: String? = null,
    ) {
        sendResponse(
            id,
            SupervisorNotificationMachine.parseJvmVerdict(decision.toInt(), errorNr),
            path,
        )
    }

    internal fun sendResponse(
        id: Long,
        verdict: JvmVerdict,
        path: String? = null,
    ) {
        val resp = SupervisorResponseSegment.of(responseSegment.managed)
        resp.setId(id)
        resp.setDecision(verdict.toWire().toByte())
        val errorNr = when (verdict) {
            is JvmVerdict.Deny -> verdict.errorNr
            is JvmVerdict.Allow, is JvmVerdict.InjectFd -> 0
        }
        resp.setErrorNr(errorNr)
        resp.setPath(path)
        writeFully(responseSegment.managed, Layouts.SUPERVISOR_RESPONSE_SIZE)
    }

    private fun writeFully(
        buf: io.mazewall.ffi.memory.ManagedSegment,
        total: Long,
    ) {
        val deadline = Deadline.afterMillis(configuredTimeoutMillis())
        var written = 0L
        while (written < total) {
            awaitReady(NativeConstants.POLLOUT, deadline)
            val remaining = total - written
            when (val res = native.memory.write(socketFd, buf.asSlice(written, remaining), remaining)) {
                is LinuxNative.SyscallResult.Error<*> -> when (res.errno) {
                    NativeConstants.EINTR, NativeConstants.EAGAIN -> Unit
                    else -> throw java.io.IOException("Supervisor validation write failed errno=${res.errno}")
                }
                is LinuxNative.SyscallResult.Success -> {
                    if (res.value <= 0 || res.value > remaining) {
                        throw java.io.IOException("Supervisor validation short write ${res.value}")
                    }
                    written += res.value
                }
            }
        }
    }

    private fun awaitReady(
        event: Short,
        deadline: Deadline,
    ) {
        while (!stopping.get()) {
            if (Thread.currentThread().isInterrupted) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("validation readiness interrupted")
            }
            val remaining = deadline.remainingMillis()
            if (remaining <= 0) throw IOException("supervisor validation readiness timed out")
            pollFd.setEvents(event)
            pollFd.setRevents(0)
            val pollTimeout = minOf(remaining, STOP_CHECK_INTERVAL_MILLIS)
            when (val result = native.raw.poll(pollFd.managed, 1, pollTimeout)) {
                is LinuxNative.SyscallResult.Success -> {
                    if (result.value == 0L) {
                        if (pollTimeout == remaining) throw IOException("supervisor validation readiness timed out")
                        continue
                    }
                    val revents = pollFd.getRevents().toInt()
                    val terminal = NativeConstants.POLLERR.toInt() or NativeConstants.POLLHUP.toInt() or NativeConstants.POLLNVAL.toInt()
                    if ((revents and terminal) != 0) throw IOException("validation peer closed during readiness wait")
                    if ((revents and event.toInt()) != 0) return
                }
                is LinuxNative.SyscallResult.Error -> if (result.errno != NativeConstants.EINTR) {
                    throw IOException("validation poll errno=${result.errno}")
                }
            }
        }
        throw IOException("supervisor installation owner terminated")
    }

    public fun sendExecRewriteAck(ok: Boolean) {
        val ack = with(arena) { allocate(1) }
        ack.writeByte(0, if (ok) 1.toByte() else 0.toByte())
        writeFully(ack, 1)
    }

    override fun close() {
        try {
            native.fileSystem.close(socketFd).getOrThrow("close validation socket")
        } finally {
            arena.close()
        }
    }

    private companion object {
        const val STOP_CHECK_INTERVAL_MILLIS = 100
        const val INPUT_BUFFER_SIZE = 8192

        fun configuredTimeoutMillis(): Long {
            val raw = System.getProperty("mazewall.supervisor.installTimeoutMillis") ?: return 30_000L
            return requireNotNull(raw.toLongOrNull()?.takeIf { it in 1..30_000 }) {
                "mazewall.supervisor.installTimeoutMillis must be in 1..30000"
            }
        }
    }
}
