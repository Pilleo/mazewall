package io.mazewall

import io.mazewall.seccomp.BpfInstruction
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.state.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.*

import io.mazewall.LinuxNative
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.ManagedSegment
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
    private val filterCache = ConcurrentHashMap<NativeCacheKey, ManagedSegment>()

    /**
     * Cache key includes [LinuxNative.engineIdentity]: native segments produced while a mock
     * engine was active are garbage for the real engine and must never be reused
     * (kernel-visible as spurious seccomp EINVAL).
     */
    private data class NativeCacheKey(val filters: List<BpfInstruction>, val engine: Any)

    /**
     * Gets a cached [ManagedSegment] for the given [filters], or computes it using
     * [LinuxNative.memory.newSockFProg] if not present.
     */
    fun getOrCompute(filters: List<BpfInstruction>): ManagedSegment {
        return filterCache.computeIfAbsent(NativeCacheKey(filters, LinuxNative.engineIdentity)) {
            with(sharedArena) { LinuxNative.memory.newSockFProg(it.filters) }
        }
    }

    /**
     * Clears the native filter cache. Used for testing.
     */
    fun clear() {
        filterCache.clear()
    }
}
