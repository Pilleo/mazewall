package io.mazewall.ffi.networking

import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.writeIntBigEndian
import io.mazewall.ffi.memory.writeIntBigEndianUnaligned
import io.mazewall.ffi.memory.writeLongBigEndian
import io.mazewall.ffi.memory.writeLongBigEndianUnaligned
import io.mazewall.ffi.memory.writeByte

/**
 * A type-safe value class wrapper around [ManagedSegment]s that restricts write operations
 * to Big Endian (Network) byte ordering.
 *
 * This enforces endianness correctness at compile-time for socket communication payloads.
 */
@JvmInline
public value class NetworkOrderBuffer(public val segment: ManagedSegment) {
    public fun writeInt(offset: Long, value: Int) {
        segment.writeIntBigEndian(offset, value)
    }

    public fun writeIntUnaligned(offset: Long, value: Int) {
        segment.writeIntBigEndianUnaligned(offset, value)
    }

    public fun writeLong(offset: Long, value: Long) {
        segment.writeLongBigEndian(offset, value)
    }

    public fun writeLongUnaligned(offset: Long, value: Long) {
        segment.writeLongBigEndianUnaligned(offset, value)
    }

    public fun writeByte(offset: Long, value: Byte) {
        segment.writeByte(offset, value)
    }
}
