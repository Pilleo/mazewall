package io.mazewall.enforcer.internal.supervisor

import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.SupervisorProcessMemoryReader
import io.mazewall.ffi.memory.SupervisorProcessMemoryWriter
import io.mazewall.platform.lifecycle.Tid
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded tracee snapshot needed to complete an intercepted accept operation. */
internal data class AcceptRequest(
    val tid: Tid,
    val fd: Int,
    val address: Long,
    val addressLength: Long,
    val flags: Int,
)

/** Copies only bounded peer bytes and reports failed tracee writes to the reply interpreter. */
internal class SupervisorPeerAddress {
    context(arena: NativeArena) fun copy(
        request: AcceptRequest,
        address: ManagedSegment,
        actualLength: Int,
    ): Boolean {
        if (request.address == 0L || request.addressLength == 0L) return true
        val requested = SupervisorProcessMemoryReader
            .readBytes(request.tid, request.addressLength, Int.SIZE_BYTES)
            ?.takeIf { it.size == Int.SIZE_BYTES } ?: return false
        val requestedLength = ByteBuffer.wrap(requested).order(ByteOrder.nativeOrder()).int
        val copyLength = minOf(actualLength, requestedLength, address.byteSize().toInt())
        return copyLength >= 0 && copyBytes(request, address, actualLength, copyLength)
    }

    context(arena: NativeArena) private fun copyBytes(
        request: AcceptRequest,
        address: ManagedSegment,
        actualLength: Int,
        copyLength: Int,
    ): Boolean {
        val bytes = ByteArray(copyLength)
        ManagedSegment.copy(address, 0, bytes, 0, copyLength)
        val copied = copyLength == 0 || SupervisorProcessMemoryWriter.writeBytes(request.tid, request.address, bytes)
        val length = ByteBuffer
            .allocate(Int.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .putInt(actualLength)
            .array()
        return copied && SupervisorProcessMemoryWriter.writeBytes(request.tid, request.addressLength, length)
    }
}
