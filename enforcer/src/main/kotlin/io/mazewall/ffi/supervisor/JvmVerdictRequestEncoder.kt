package io.mazewall.ffi.supervisor

import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.native
import io.mazewall.ffi.memory.writeByte
import io.mazewall.ffi.memory.writeInt
import io.mazewall.ffi.memory.writeIntUnaligned
import io.mazewall.ffi.memory.writeLong
import io.mazewall.ffi.memory.writeLongUnaligned
import io.mazewall.ffi.networking.NetworkOrderBuffer
import java.nio.charset.StandardCharsets

/** Arena-scoped wire encoding for the JVM verdict request frame. */
internal object JvmVerdictRequestEncoder {
    internal data class Encoded(
        val buffer: ManagedSegment,
        val size: Long,
    )

    context(arena: NativeArena) fun encode(
        id: Long,
        tid: Int,
        audit: Int,
        ppid: Int,
        nr: Int,
        args: LongArray,
        path: String?,
        sockaddr: ByteArray?,
    ): Encoded {
        val pathBytes = path?.toByteArray(StandardCharsets.UTF_8)
        val payload = pathBytes ?: sockaddr
        val size = (HEADER_SIZE + if (payload == null) MAX_ARGS * (SIZE_BYTE + Long.SIZE_BYTES) else ARG_HEADER_SIZE + payload.size).toLong()
        val buffer = arena.allocate(size)
        val output = NetworkOrderBuffer(buffer.native)
        var offset = 0L
        output.writeLong(offset, id)
        offset += Long.SIZE_BYTES
        output.writeInt(offset, tid)
        offset += SIZE_INT
        output.writeInt(offset, audit)
        offset += SIZE_INT
        output.writeInt(offset, ppid)
        offset += SIZE_INT
        output.writeInt(offset, nr)
        offset += SIZE_INT
        if (payload != null) {
            writePayload(output, buffer, offset, if (pathBytes != null) ARG_TYPE_STRING else ARG_TYPE_SOCKADDR, payload)
        } else {
            output.writeInt(offset, MAX_ARGS)
            offset += SIZE_INT
            args.forEach { arg ->
                output.writeByte(offset, ARG_TYPE_LONG)
                offset += SIZE_BYTE
                output.writeLongUnaligned(offset, arg)
                offset += Long.SIZE_BYTES
            }
        }
        return Encoded(buffer, size)
    }

    private fun writePayload(
        output: NetworkOrderBuffer,
        buffer: ManagedSegment,
        start: Long,
        type: Byte,
        bytes: ByteArray,
    ) {
        var offset = start
        output.writeInt(offset, ONE_ARG)
        offset += SIZE_INT
        output.writeByte(offset, type)
        offset += SIZE_BYTE
        output.writeIntUnaligned(offset, bytes.size)
        offset += SIZE_INT
        ManagedSegment.copy(bytes, 0, buffer, offset, bytes.size)
    }

    private const val HEADER_SIZE = 28
    private const val ARG_HEADER_SIZE = 5
    private const val SIZE_INT = 4
    private const val SIZE_BYTE = 1
    private const val ONE_ARG = 1
    private const val MAX_ARGS = 6
    private const val ARG_TYPE_LONG: Byte = 0
    private const val ARG_TYPE_STRING: Byte = 1
    private const val ARG_TYPE_SOCKADDR: Byte = 2
}
