package io.mazewall.ffi.memory

import java.lang.foreign.Arena

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Scoped context for native memory management.
 *
 * This utility ensures that [Arena] lifecycles are deterministically managed
 * using [Arena.ofConfined]. The [block] receives the arena as a receiver,
 * preventing segments from being easily leaked outside the scope.
 */
@OptIn(ExperimentalContracts::class)
public inline fun <T> nativeScope(crossinline block: Arena.() -> T): T {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    return Arena.ofConfined().use { it.block() }
}
