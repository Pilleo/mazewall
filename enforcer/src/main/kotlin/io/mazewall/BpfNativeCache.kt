package io.mazewall

import io.mazewall.LinuxNative
import io.mazewall.enforcer.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.state.*
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.seccomp.BpfInstruction
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
    private val sharedArena = NativeArena.ofShared()
    private const val MAX_ENTRIES = 256

    private class BpfFilterLruMap(
        initialCapacity: Int,
        loadFactor: Float,
        accessOrder: Boolean,
    ) : LinkedHashMap<NativeCacheKey, ManagedSegment>(initialCapacity, loadFactor, accessOrder) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<NativeCacheKey, ManagedSegment>): Boolean = size > MAX_ENTRIES
    }

    private val filterCache = BpfFilterLruMap(64, 0.75f, true)

    /**
     * Cache key includes [LinuxNative.engineIdentity]: native segments produced while a mock
     * engine was active are garbage for the real engine and must never be reused
     * (kernel-visible as spurious seccomp EINVAL).
     */
    private data class NativeCacheKey(
        val filters: List<BpfInstruction>,
        val engine: Any,
    )

    /**
     * Gets a cached [ManagedSegment] for the given [filters], or computes it using
     * the current captured native engine if not present.
     */
    fun getOrCompute(filters: List<BpfInstruction>): ManagedSegment {
        return getOrCompute(filters, LinuxNative.snapshot())
    }

    fun getOrCompute(
        filters: List<BpfInstruction>,
        native: NativeEngine,
    ): ManagedSegment {
        val identity = (native as? RuntimeSnapshot)?.engineIdentity ?: native
        val key = NativeCacheKey(filters, identity)
        synchronized(filterCache) {
            filterCache[key]?.let { return it }
        }
        val computed = with(sharedArena) { native.memory.newSockFProg(filters) }
        synchronized(filterCache) {
            return filterCache.getOrPut(key) { computed }
        }
    }

    /**
     * Clears the native filter cache. Used for testing.
     */
    fun clear() {
        synchronized(filterCache) {
            filterCache.clear()
        }
    }
}
