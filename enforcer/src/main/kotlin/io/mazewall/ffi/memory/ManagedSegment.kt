package io.mazewall.ffi.memory

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Models ownership and confinement of a native memory segment.
 *
 * ARCHITECTURAL INVARIANT: ManagedSegment ensures that FFM interactions are type-safe
 * and scope-aware. It prevents passing segments with incorrect layouts or invalid
 * lifecycles to native system calls, mitigating memory corruption risks at the JVM boundary.
 */
public sealed interface ManagedSegment {
    /**
     * Returns the raw native address of this segment.
     */
    public fun address(): Long

    /**
     * Returns the size of this segment in bytes.
     */
    public fun byteSize(): Long

    public companion object {
        /**
         * Represents a NULL memory segment.
         */
        public val NULL: ManagedSegment = SharedSegment(MemorySegment.NULL)

        /**
         * Copies a byte array into a managed segment.
         */
        public fun copy(src: ByteArray, srcOffset: Int, dest: ManagedSegment, destOffset: Long, size: Int) {
            MemorySegment.copy(src, srcOffset, dest.native, ValueLayout.JAVA_BYTE, destOffset, size)
        }

        /**
         * Copies from a managed segment into a byte array.
         */
        public fun copy(src: ManagedSegment, srcOffset: Long, dest: ByteArray, destOffset: Int, size: Int) {
            MemorySegment.copy(src.native, ValueLayout.JAVA_BYTE, srcOffset, dest, destOffset, size)
        }
    }
}

/**
 * A segment that is confined to a single thread and a deterministic lifecycle ([Arena]).
 */
@JvmInline
public value class ConfinedSegment(internal val native: MemorySegment) : ManagedSegment {
    override fun address(): Long = native.address()
    override fun byteSize(): Long = native.byteSize()
}

/**
 * A segment that can be shared across multiple threads.
 */
@JvmInline
public value class SharedSegment(internal val native: MemorySegment) : ManagedSegment {
    override fun address(): Long = native.address()
    override fun byteSize(): Long = native.byteSize()
}

/**
 * Internal access to the raw FFM segment for use within the FFI boundary.
 */
internal val ManagedSegment.native: MemorySegment
    get() = when (this) {
        is ConfinedSegment -> this.native
        is SharedSegment -> this.native
    }

public fun ManagedSegment.readByte(offset: Long): Byte = this.native.readByte(offset)
public fun ManagedSegment.writeByte(offset: Long, value: Byte): Unit = this.native.writeByte(offset, value)

public fun ManagedSegment.readShort(offset: Long): Short = this.native.readShort(offset)
public fun ManagedSegment.writeShort(offset: Long, value: Short): Unit = this.native.writeShort(offset, value)

public fun ManagedSegment.readInt(offset: Long): Int = this.native.readInt(offset)
public fun ManagedSegment.writeInt(offset: Long, value: Int): Unit = this.native.writeInt(offset, value)

public fun ManagedSegment.readIntUnaligned(offset: Long): Int = this.native.readIntUnaligned(offset)
public fun ManagedSegment.writeIntUnaligned(offset: Long, value: Int): Unit = this.native.writeIntUnaligned(offset, value)

public fun ManagedSegment.readLong(offset: Long): Long = this.native.readLong(offset)
public fun ManagedSegment.writeLong(offset: Long, value: Long): Unit = this.native.writeLong(offset, value)

public fun ManagedSegment.readLongUnaligned(offset: Long): Long = this.native.readLongUnaligned(offset)
public fun ManagedSegment.writeLongUnaligned(offset: Long, value: Long): Unit = this.native.writeLongUnaligned(offset, value)

public fun ManagedSegment.fill(value: Byte): Unit { this.native.fill(value) }

public val NativeArena.unwrap: java.lang.foreign.Arena get() = this.arena

public val ManagedSegment.unwrap: java.lang.foreign.MemorySegment get() = this.native
