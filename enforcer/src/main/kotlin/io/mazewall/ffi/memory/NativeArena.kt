package io.mazewall.ffi.memory

import java.lang.foreign.Arena

/**
 * An opaque wrapper for [java.lang.foreign.Arena] to isolate FFM from domain logic.
 */
public class NativeArena internal constructor(internal val arena: Arena) : AutoCloseable {
    override fun close() {
        arena.close()
    }

    public val isAlive: Boolean get() = arena.scope().isAlive

    public companion object {
        public fun ofConfined(): NativeArena = NativeArena(Arena.ofConfined())
        public fun ofShared(): NativeArena = NativeArena(Arena.ofShared())
        public fun global(): NativeArena = NativeArena(Arena.global())
    }
}
