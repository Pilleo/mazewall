package io.mazewall

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.state.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.*

import io.mazewall.core.Arch
import java.util.concurrent.ConcurrentHashMap

/**
 * Global cache for compiled BPF filters.
 */
internal object PolicyCompilationCache {
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
