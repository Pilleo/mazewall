package io.mazewall.enforcer

/**
 * Internal registry for tracking seccomp and Landlock state bound to the current thread.
 *
 * It captures the current [ContainerState] reflecting the immutable OS-level
 * restrictions applied to the thread.
 */
internal object ThreadStateRegistry {
    // INVARIANT: ThreadLocals are INTENTIONALLY not cleared between tasks.
    // Seccomp filters are permanent for the OS thread lifetime.
    // Do NOT add cleanup in task wrappers; it would give a false sense of
    // isolation between tasks on the same thread. See code_issues_backlog.md
    // "Permanent thread pool contamination" for the known limitation and
    // the correct fix strategy (scope checks, not cleanup).

    private var stateInternal by threadLocal { ContainerState() }

    /**
     * The current security state of the active thread.
     */
    var state: ContainerState
        get() = stateInternal
        set(value) {
            stateInternal = value
        }

    /**
     * Explicitly disables state sanitization.
     * Thread-local states reflect the immutable OS-level restrictions applied
     * to the OS thread (LWP). Clearing them would cause synchronization loss
     * between JVM state and kernel state, leading to redundant filter installations.
     */
    fun sanitize() {
        throw UnsupportedOperationException(
            "Sanitization of ThreadStateRegistry is intentionally disabled. " +
                "OS-level sandbox restrictions are permanent for the thread's lifetime."
        )
    }
}
