package io.contained

/**
 * Common interface for seccomp installation mechanisms.
 */
interface SeccompEngine {
    /**
     * Installs the given [policy] onto the calling thread.
     * Throws [IllegalStateException] if installation fails.
     */
    fun install(policy: Policy)

    /**
     * Returns true if this engine is supported on the current system.
     */
    val isSupported: Boolean
}
