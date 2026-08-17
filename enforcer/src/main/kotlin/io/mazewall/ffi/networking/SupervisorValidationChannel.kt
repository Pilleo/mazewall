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
import io.mazewall.ffi.memory.NativeArena
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
        val resp = SupervisorResponseSegment.of(responseSegment.managed)
        resp.setId(id)
        resp.setDecision(decision)
        resp.setErrorNr(errorNr)
        resp.setPath(path)
        LinuxNative.memory.write(socketFd, responseSegment.managed, Layouts.SUPERVISOR_RESPONSE_SIZE)
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
