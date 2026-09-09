package io.mazewall.ffi.networking

import io.mazewall.LinuxNative
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.SocketIo
import io.mazewall.enforcer.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.state.*
import io.mazewall.enforcer.supervisor.JvmVerdict
import io.mazewall.enforcer.supervisor.SupervisorNotificationMachine
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.SupervisorResponseSegment
import io.mazewall.ffi.memory.writeByte
import java.io.InputStream

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
    private val input = SupervisorSocketInputStream(socketFd, arena)
    private val pollFd = io.mazewall.ffi.memory.PollFdSegment.of(arena.allocate(Layouts.POLLFD)).apply {
        setFd(socketFd.value)
        setEvents(io.mazewall.ffi.NativeConstants.POLLIN)
    }
    public val inputStream: InputStream = object : InputStream() {
        override fun read(): Int {
            awaitReadable()
            return input.read()
        }

        override fun read(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (length == 0) return 0
            awaitReadable()
            return input.read(bytes, offset, length)
        }
    }
    private val responseSegment = with(arena) { SupervisorResponseSegment.allocate() }

    /** Requests reactor-owned cleanup without closing an arena under an active native read. */
    internal fun requestStop() {
        stopping.set(true)
    }

    private fun awaitReadable() {
        while (!stopping.get()) {
            when (val result = native.raw.poll(pollFd.managed, 1, 100)) {
                is LinuxNative.SyscallResult.Success -> if (result.value > 0) return
                is LinuxNative.SyscallResult.Error -> if (result.errno != io.mazewall.ffi.NativeConstants.EINTR) {
                    throw java.io.IOException("validation poll errno=${result.errno}")
                }
            }
        }
        throw java.io.IOException("supervisor installation owner terminated")
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
        when (val res = SocketIo.writeFully(LinuxNative.memory, socketFd, buf, total)) {
            is LinuxNative.SyscallResult.Error<*> -> error("Supervisor validation write failed errno=${res.errno}")
            is LinuxNative.SyscallResult.Success -> check(res.value == total) { "Supervisor validation short write ${res.value}" }
        }
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
}
