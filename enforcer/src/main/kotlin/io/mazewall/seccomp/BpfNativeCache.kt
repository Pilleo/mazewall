package io.mazewall.seccomp

import io.mazewall.LinuxNative
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import java.util.concurrent.ConcurrentHashMap

/**
 * Global cache for native BPF filter segments.
 *
 * This cache ensures that identical BPF filter instructions are compiled and
 * allocated in native memory only once, using a shared [NativeArena]. This prevents
 * native memory leaks and reduces overhead during high-concurrency filter
 * installations.
 */
internal object BpfNativeCache {
    private val sharedArena = NativeArena.ofShared()
    private val filterCache = ConcurrentHashMap<List<BpfInstruction>, ManagedSegment>()

    /**
     * Gets a cached [ManagedSegment] for the given [filters], or computes it using
     * [LinuxNative.memory.newSockFProg] if not present.
     */
    fun getOrCompute(filters: List<BpfInstruction>): ManagedSegment {
        return filterCache.computeIfAbsent(filters) {
            with(sharedArena) { LinuxNative.memory.newSockFProg(it) }
        }
    }

    /**
     * Clears the native filter cache. Used for testing.
     */
    fun clear() {
        filterCache.clear()
    }
}
