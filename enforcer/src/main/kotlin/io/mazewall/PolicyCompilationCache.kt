package io.mazewall

import io.mazewall.core.Arch
import java.util.concurrent.ConcurrentHashMap

/**
 * Global cache for compiled BPF filters.
 */
public object PolicyCompilationCache {
    private data class CacheKey(
        val definition: PolicyDefinition<*>,
        val arch: Arch
    )

    private val cache = ConcurrentHashMap<CacheKey, CompiledSandbox<*>>()

    fun <S : PolicyScope> getOrCompile(
        definition: PolicyDefinition<S>,
        arch: Arch
    ): CompiledSandbox<S> {
        val key = CacheKey(definition, arch)
        @Suppress("UNCHECKED_CAST")
        return cache.computeIfAbsent(key) {
            definition.compile(arch)
        } as CompiledSandbox<S>
    }

    fun clear() {
        cache.clear()
    }
}
