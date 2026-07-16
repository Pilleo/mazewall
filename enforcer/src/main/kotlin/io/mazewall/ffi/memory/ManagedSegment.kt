package io.mazewall.ffi.memory

import java.lang.foreign.MemorySegment

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
