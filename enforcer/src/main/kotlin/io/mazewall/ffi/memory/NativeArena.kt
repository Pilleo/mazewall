package io.mazewall.ffi.memory

import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment

/**
 * An opaque wrapper for [java.lang.foreign.Arena] to isolate FFM from domain logic.
 */
public class NativeArena internal constructor(
    internal val arena: Arena,
    internal val isShared: Boolean
) : AutoCloseable {
    override fun close() {
        arena.close()
    }

    public val isAlive: Boolean get() = arena.scope().isAlive

    private fun wrap(segment: MemorySegment): ManagedSegment =
        if (isShared) SharedSegment(segment) else ConfinedSegment(segment)

    public fun allocate(byteSize: Long): ManagedSegment = wrap(arena.allocate(byteSize))
    public fun allocate(layout: MemoryLayout): ManagedSegment = wrap(arena.allocate(layout))
    public fun allocate(layout: MemoryLayout, count: Long): ManagedSegment = wrap(arena.allocate(layout, count))

    public fun allocateFrom(value: String): ManagedSegment = wrap(arena.allocateFrom(value))

    public companion object {
        public fun ofConfined(): NativeArena = NativeArena(Arena.ofConfined(), isShared = false)
        public fun ofShared(): NativeArena = NativeArena(Arena.ofShared(), isShared = true)
        public fun global(): NativeArena = NativeArena(Arena.global(), isShared = true)
    }
}
