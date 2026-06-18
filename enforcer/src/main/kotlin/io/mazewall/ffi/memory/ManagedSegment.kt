package io.mazewall.ffi.memory

import java.lang.foreign.MemorySegment

/**
 * Models ownership and confinement of a [MemorySegment].
 *
 * ARCHITECTURAL INVARIANT: ManagedSegment ensures that FFM interactions are type-safe
 * and scope-aware. It prevents passing segments with incorrect layouts or invalid
 * lifecycles to native system calls, mitigating memory corruption risks at the JVM boundary.
 */
public sealed interface ManagedSegment {
    public val segment: MemorySegment
}

/**
 * A segment that is confined to a single thread and a deterministic lifecycle ([Arena]).
 */
@JvmInline
public value class ConfinedSegment(override val segment: MemorySegment) : ManagedSegment

/**
 * A segment that can be shared across multiple threads.
 */
@JvmInline
public value class SharedSegment(override val segment: MemorySegment) : ManagedSegment
