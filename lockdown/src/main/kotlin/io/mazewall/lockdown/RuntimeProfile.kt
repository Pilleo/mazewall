package io.mazewall.lockdown

/**
 * Target JVM runtime model for process-wide lockdown policies.
 *
 * Choosing a profile explicitly avoids silent mismatches between JIT code-cache allocation
 * and AOT/W^X enforcement.
 */
public enum class RuntimeProfile {
    /**
     * Standard HotSpot JVM with JIT compilation enabled. Keeps anonymous `PROT_EXEC`
     * memory mappings permitted so JIT code cache growth continues working after install.
     */
    HOTSPOT_JIT,

    /**
     * Ahead-of-time W^X runtime such as GraalVM Native Image. Denies new executable
     * memory mappings (`mmap`/`mprotect` with `PROT_EXEC`) in addition to process creation.
     */
    NATIVE_IMAGE,
}
