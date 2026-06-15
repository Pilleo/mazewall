package io.mazewall.ffi.memory

import java.lang.foreign.Arena

/**
 * Scoped context for native memory management.
 *
 * This utility ensures that [Arena] lifecycles are deterministically managed
 * using [Arena.ofConfined]. The [block] receives the arena as a receiver,
 * preventing segments from being easily leaked outside the scope.
 */
public inline fun <T> nativeScope(crossinline block: Arena.() -> T): T =
    Arena.ofConfined().use { it.block() }
