package io.mazewall.ffi.memory

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Scoped context for native memory management.
 *
 * This utility ensures that [NativeArena] lifecycles are deterministically managed
 * using [NativeArena.ofConfined]. The [block] receives the native arena as a receiver,
 * preventing segments from being easily leaked outside the scope.
 */
@OptIn(ExperimentalContracts::class)
public inline fun <T> nativeScope(crossinline block: NativeArena.() -> T): T {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    return NativeArena.ofConfined().use { it.block() }
}
