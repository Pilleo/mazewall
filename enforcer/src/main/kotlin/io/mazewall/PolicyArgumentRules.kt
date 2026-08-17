package io.mazewall

/**
 * Effective argument-inspection rules for a [Policy], independent of syscall
 * allow/deny maps. Operators can read this instead of inferring hidden builder flags.
 */
public data class PolicyArgumentRules(
    /** When false, BPF denies `mmap`/`mprotect` with `PROT_EXEC`. */
    val allowExecutableMappings: Boolean,
    /** When true, BPF requires `CLONE_THREAD` on `clone` / `clone3` fallback. */
    val inspectNonThreadClone: Boolean,
    /** When true, BPF inspects `prctl` option codes (TOCTOU-limited). */
    val inspectUnsafePrctl: Boolean,
) {
    public companion object {
        public fun of(definition: PolicyDefinition<*>): PolicyArgumentRules =
            PolicyArgumentRules(
                allowExecutableMappings = definition.allowMmapExec,
                inspectNonThreadClone = !definition.allowNonThreadClone,
                inspectUnsafePrctl = !definition.allowUnsafePrctl,
            )
    }
}
