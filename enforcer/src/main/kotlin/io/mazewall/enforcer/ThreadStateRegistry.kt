package io.mazewall.enforcer

import io.mazewall.core.SandboxedPath
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall

/**
 * Internal registry for tracking seccomp and Landlock state bound to the current thread.
 *
 * It maps each [Syscall] to its enforced [SeccompAction]. When filter stacking occurs,
 * the highest priority action (e.g., ACT_KILL_PROCESS > ACT_ALLOW) takes precedence
 * according to BPF semantics.
 */
internal object ThreadStateRegistry {
    // INVARIANT: ThreadLocals are INTENTIONALLY not cleared between tasks.
    // Seccomp filters are permanent for the OS thread lifetime.
    // Do NOT add cleanup in task wrappers; it would give a false sense of
    // isolation between tasks on the same thread. See code_issues_backlog.md
    // "Permanent thread pool contamination" for the known limitation and
    // the correct fix strategy (scope checks, not cleanup).

    var syscallActions by threadLocal<Map<Syscall, SeccompAction>> { emptyMap() }
    var defaultAction by threadLocal { SeccompAction.ACT_ALLOW }
    var allowsMmapExec by threadLocal { true }
    var allowsNonThreadClone by threadLocal { true }
    var allowsUnsafePrctl by threadLocal { true }
    var allowedSyscalls by threadLocal<Set<Syscall>?> { null }
    var filterDepth by threadLocal { 0 }
    var landlockAppliedReads by threadLocal<Set<SandboxedPath>?> { null }
    var landlockAppliedWrites by threadLocal<Set<SandboxedPath>?> { null }

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
