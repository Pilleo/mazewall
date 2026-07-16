package io.mazewall.ffi.memory

import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment

/**
 * An opaque wrapper for [java.lang.foreign.Arena] to isolate FFM from domain logic.
 */
public class NativeArena internal constructor(public val arena: Arena) : AutoCloseable {
    override fun close() {
        arena.close()
    }

    public val isAlive: Boolean get() = arena.scope().isAlive

    public fun allocate(byteSize: Long): ManagedSegment = ConfinedSegment(arena.allocate(byteSize))
    public fun allocate(layout: MemoryLayout): ManagedSegment = ConfinedSegment(arena.allocate(layout))
    public fun allocate(layout: MemoryLayout, count: Long): ManagedSegment = ConfinedSegment(arena.allocate(layout, count))

    public fun allocateFrom(value: String): ManagedSegment = ConfinedSegment(arena.allocateFrom(value))

    public companion object {
        public fun ofConfined(): NativeArena = NativeArena(Arena.ofConfined())
        public fun ofShared(): NativeArena = NativeArena(Arena.ofShared())
        public fun global(): NativeArena = NativeArena(Arena.global())
    }
}
