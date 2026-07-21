package io.mazewall.seccomp

import io.mazewall.LinuxNative
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.native
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.util.concurrent.ConcurrentHashMap

/**
 * Global cache for native BPF filter segments.
 *
 * This cache ensures that identical BPF filter instructions are compiled and
 * allocated in native memory only once, using a shared [Arena]. This prevents
 * native memory leaks and reduces overhead during high-concurrency filter
 * installations.
 */
internal object BpfNativeCache {
    private val sharedArena = Arena.ofShared()
    private val filterCache = ConcurrentHashMap<List<BpfInstruction>, MemorySegment>()

    /**
     * Gets a cached [MemorySegment] for the given [filters], or computes it using
     * [LinuxNative.memory.newSockFProg] if not present.
     */
    fun getOrCompute(filters: List<BpfInstruction>): MemorySegment {
        return filterCache.computeIfAbsent(filters) {
            val nativeArena = NativeArena(sharedArena, isShared = true)
            with(nativeArena) { LinuxNative.memory.newSockFProg(it) }.native
        }
    }

    /**
     * Clears the native filter cache. Used for testing.
     */
    fun clear() {
        filterCache.clear()
    }
}
