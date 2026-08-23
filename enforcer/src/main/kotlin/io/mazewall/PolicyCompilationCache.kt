package io.mazewall

import io.mazewall.core.Arch
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall

/**
 * Memoizes compiled+verified BPF programs so repeated installs of the same policy (e.g. every task
 * through a wrapped executor) skip recompilation.
 *
 * INVARIANT (issue-20260823-171953): the cache key is the PROGRAM-RELEVANT PROJECTION of a
 * [PolicyDefinition] — default action, syscall actions, and arg-inspection flags. Landlock
 * filesystem paths must NOT participate: they never influence BPF output, and including them
 * made dynamic policies (e.g. IterativeProfiler discovery) leak one dead entry per iteration.
 */
internal object PolicyCompilationCache {
    private data class CacheKey(
        val defaultAction: SeccompAction,
        val syscallActions: Map<Syscall, SeccompAction>,
        val allowMmapExec: Boolean,
        val allowNonThreadClone: Boolean,
        val allowUnsafePrctl: Boolean,
        val lockIntelCet: Boolean,
        val arch: Arch,
    ) {
        constructor(definition: PolicyDefinition<*>, arch: Arch) : this(
            definition.defaultAction,
            definition.syscallActions,
            definition.allowMmapExec,
            definition.allowNonThreadClone,
            definition.allowUnsafePrctl,
            definition.lockIntelCet,
            arch,
        )
    }

    /** Belt-and-braces cap; behavioral projections are naturally few. Callers hold processLock. */
    private const val MAX_ENTRIES = 256

    private val cache = object : LinkedHashMap<CacheKey, CompiledSandbox<*>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, CompiledSandbox<*>>): Boolean =
            size > MAX_ENTRIES
    }

    fun <S : PolicyScope> getOrCompile(
        definition: PolicyDefinition<S>,
        arch: Arch
    ): CompiledSandbox<S> {
        val key = CacheKey(definition, arch)
        synchronized(cache) {
            @Suppress("UNCHECKED_CAST")
            cache[key]?.let {
                return it as CompiledSandbox<S>
            }
        }
        // Compile OUTSIDE the lock: BpfFilter.build is pure CPU work on immutable inputs.
        val compiled = definition.compile(arch)
        synchronized(cache) {
            @Suppress("UNCHECKED_CAST")
            return cache.getOrPut(key) { compiled } as CompiledSandbox<S>
        }
    }

    fun clear() {
        synchronized(cache) { cache.clear() }
    }

    internal fun entryCount(): Int = synchronized(cache) { cache.size }
}
