package io.mazewall.ffi.networking

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.state.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.*

import io.mazewall.LinuxNative
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.enforcer.supervisor.JvmVerdict
import io.mazewall.enforcer.supervisor.SupervisorNotificationMachine
import io.mazewall.ffi.memory.SupervisorResponseSegment
import io.mazewall.ffi.memory.writeByte
import java.io.InputStream

/**
 * Encapsulates the FFM/Arena details and response structures for the Supervisor JVM Validation process.
 * This class exposes a high-level safe interface to the enforcer packages, hiding MemorySegments and Arenas.
 */
public class SupervisorValidationChannel(
    private val socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>
) : AutoCloseable {
    private val arena = NativeArena.ofShared()
    public val inputStream: InputStream = SupervisorSocketInputStream(socketFd, arena)
    private val responseSegment = with(arena) { SupervisorResponseSegment.allocate() }

    public fun sendResponse(id: Long, decision: Byte, errorNr: Int, path: String? = null) {
        sendResponse(
            id,
            SupervisorNotificationMachine.parseJvmVerdict(decision.toInt(), errorNr)
                ?: JvmVerdict.Deny(io.mazewall.ffi.NativeConstants.EPERM),
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

    private fun writeFully(buf: io.mazewall.ffi.memory.ManagedSegment, total: Long) {
        var offset = 0L
        while (offset < total) {
            val remaining = total - offset
            val slice = buf.asSlice(offset, remaining)
            when (val res = LinuxNative.memory.write(socketFd, slice, remaining)) {
                is LinuxNative.SyscallResult.Error<*> -> {
                    if (res.errno == NativeConstants.EINTR) continue
                    error("Supervisor validation write failed errno=${res.errno}")
                }
                is LinuxNative.SyscallResult.Success -> {
                    check(res.value > 0L) { "Supervisor validation write returned ${res.value}" }
                    offset += res.value
                }
            }
        }
    }

    public fun sendExecRewriteAck(ok: Boolean) {
        val ack = with(arena) { allocate(1) }
        ack.writeByte(0, if (ok) 1.toByte() else 0.toByte())
        LinuxNative.memory.write(socketFd, ack, 1)
    }

    override fun close() {
        try {
            inputStream.close()
        } finally {
            arena.close()
        }
    }
}
