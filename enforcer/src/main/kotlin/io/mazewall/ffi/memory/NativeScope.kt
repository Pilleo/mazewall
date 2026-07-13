package io.mazewall.ffi.memory

import java.lang.foreign.Arena

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

@PublishedApi
internal val activeArena: ThreadLocal<Arena> = ThreadLocal()

/**
 * Scoped context for native memory management.
 *
 * This utility ensures that [Arena] lifecycles are deterministically managed
 * using [Arena.ofConfined]. The [block] receives the arena as a receiver,
 * preventing segments from being easily leaked outside the scope.
 *
 * If a [nativeScope] is already active on the current thread, the existing
 * arena is reused instead of creating a new one.
 */
@OptIn(ExperimentalContracts::class)
public inline fun <T> nativeScope(block: Arena.() -> T): T {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    val current = activeArena.get()
    if (current != null) {
        return current.block()
    }

    return Arena.ofConfined().use { arena ->
        activeArena.set(arena)
        try {
            arena.block()
        } finally {
            activeArena.remove()
        }
    }
}
