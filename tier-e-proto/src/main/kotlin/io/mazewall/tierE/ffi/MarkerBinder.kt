package io.mazewall.tierE.ffi

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.invoke.MethodHandle
import java.lang.foreign.ValueLayout

/**
 * FFM binding to `mazewall_context_marker(uint32)` inside a marker library
 * (libmazewall_context[_usdt].so). Used by the WP-05 stress driver to declare
 * semantic scopes from real JVM platform threads. UNTRUSTED metadata contract
 * applies to whatever this writes — detection plane only.
 */
public class MarkerBinder(sharedObjectPath: String) : AutoCloseable {
    private val arena: Arena = Arena.ofShared()
    private val lookup: SymbolLookup = SymbolLookup.libraryLookup(sharedObjectPath, arena)
    private val linker: Linker = Linker.nativeLinker()
    private val handle: MethodHandle = linker.downcallHandle(
        lookup.find("mazewall_context_marker")
            .orElseThrow { IllegalStateException("marker symbol missing in $sharedObjectPath") },
        FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT),
    )

    public fun mark(contextId: UInt) {
        handle.invoke(contextId.toInt())
    }

    override fun close() {
        // Library lifetime == process lifetime for the stress driver; the
        // shared arena is never freed by design.
    }
}
