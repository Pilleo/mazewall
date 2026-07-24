package io.mazewall.ffi.networking

import io.mazewall.LinuxNative
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.SupervisorResponseSegment
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

    public fun sendResponse(id: Long, decision: Byte, errorNr: Int) {
        val resp = SupervisorResponseSegment.of(responseSegment.managed)
        resp.setId(id)
        resp.setDecision(decision)
        resp.setErrorNr(errorNr)
        LinuxNative.memory.write(socketFd, responseSegment.managed, Layouts.SUPERVISOR_RESPONSE_SIZE)
    }

    override fun close() {
        try {
            inputStream.close()
        } finally {
            arena.close()
        }
    }
}
