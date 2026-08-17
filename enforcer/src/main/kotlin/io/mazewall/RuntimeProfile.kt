package io.mazewall

/**
 * JVM / image flavour that decides implicit argument filters on a process baseline.
 *
 * Preset names such as [Policy.NO_NETWORK] do not say whether `mmap(PROT_EXEC)` is
 * allowed. Factories on [ProcessPolicies] take an explicit [RuntimeProfile] instead
 * of relying on [PolicyBuilder] defaults.
 */
public enum class RuntimeProfile {
    /**
     * HotSpot with a live JIT. Executable mappings must stay allowed so the
     * compiler threads can grow the code cache after process-wide install.
     */
    HOTSPOT_JIT,

    /**
     * GraalVM Native Image / other W^X runtime. `mmap`/`mprotect` with
     * `PROT_EXEC` stay denied after install; there is no JIT code cache.
     */
    NATIVE_IMAGE,
    ;

    /** Whether this runtime still needs `mmap(PROT_EXEC)` after containment. */
    public val allowsExecutableMappings: Boolean
        get() =
            when (this) {
                HOTSPOT_JIT -> true
                NATIVE_IMAGE -> false
            }
}
