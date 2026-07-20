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

    /**
     * Internal access to the raw FFM segment for use within the FFI boundary.
     */
    public val native: MemorySegment

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
 * A segment that is confined to a single thread and a deterministic lifecycle.
 */
@JvmInline
public value class ConfinedSegment(override val native: MemorySegment) : ManagedSegment {
    override fun address(): Long = native.address()
    override fun byteSize(): Long = native.byteSize()
}

/**
 * A segment that can be shared across multiple threads.
 */
@JvmInline
public value class SharedSegment(override val native: MemorySegment) : ManagedSegment {
    override fun address(): Long = native.address()
    override fun byteSize(): Long = native.byteSize()
}

public fun ManagedSegment.fill(value: Byte) { this.native.fill(value) }
public fun ManagedSegment.asSlice(offset: Long, newSize: Long): ManagedSegment = when (this) { is ConfinedSegment -> ConfinedSegment(this.native.asSlice(offset, newSize)); is SharedSegment -> SharedSegment(this.native.asSlice(offset, newSize)) }

public operator fun ManagedSegment.get(layout: java.lang.foreign.ValueLayout.OfInt, offset: Long): Int = this.native.get(layout, offset)
public operator fun ManagedSegment.get(layout: java.lang.foreign.ValueLayout.OfLong, offset: Long): Long = this.native.get(layout, offset)
public operator fun ManagedSegment.get(layout: java.lang.foreign.AddressLayout, offset: Long): java.lang.foreign.MemorySegment = this.native.get(layout, offset)

public operator fun ManagedSegment.set(layout: java.lang.foreign.ValueLayout.OfInt, offset: Long, value: Int) { this.native.set(layout, offset, value) }
public operator fun ManagedSegment.set(layout: java.lang.foreign.ValueLayout.OfLong, offset: Long, value: Long) { this.native.set(layout, offset, value) }
public operator fun ManagedSegment.set(layout: java.lang.foreign.AddressLayout, offset: Long, value: java.lang.foreign.MemorySegment) { this.native.set(layout, offset, value) }
public operator fun ManagedSegment.set(layout: java.lang.foreign.AddressLayout, offset: Long, value: ManagedSegment) { this.native.set(layout, offset, value.native) }

public fun ManagedSegment.getString(offset: Long): String = this.native.getString(offset)

public operator fun ManagedSegment.set(layout: java.lang.foreign.ValueLayout.OfByte, offset: Long, value: Byte) { this.native.set(layout, offset, value) }
public operator fun ManagedSegment.set(layout: java.lang.foreign.ValueLayout.OfShort, offset: Long, value: Short) { this.native.set(layout, offset, value) }
public operator fun ManagedSegment.get(layout: java.lang.foreign.ValueLayout.OfByte, offset: Long): Byte = this.native.get(layout, offset)
public operator fun ManagedSegment.get(layout: java.lang.foreign.ValueLayout.OfShort, offset: Long): Short = this.native.get(layout, offset)
public fun ManagedSegment.getString(offset: Long, charset: java.nio.charset.Charset): String = this.native.getString(offset, charset)
