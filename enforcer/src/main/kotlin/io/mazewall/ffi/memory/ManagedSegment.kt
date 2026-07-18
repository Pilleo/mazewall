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

public fun ManagedSegment.slice(offset: Long, size: Long): ManagedSegment = ConfinedSegment(this.native.asSlice(offset, size))

public fun ManagedSegment.fill(value: Byte) {
    this.native.fill(value)
}

/**
 * A segment that can be shared across multiple threads.
 */
@JvmInline
public value class SharedSegment(override val native: MemorySegment) : ManagedSegment {
    override fun address(): Long = native.address()
    override fun byteSize(): Long = native.byteSize()
}
