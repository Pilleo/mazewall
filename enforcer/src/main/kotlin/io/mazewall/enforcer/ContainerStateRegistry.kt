package io.mazewall.enforcer

import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Internal registry for tracking seccomp and Landlock state across threads.
 *
 * It maps each [Syscall] to its enforced [SeccompAction]. When filter stacking occurs,
 * the highest priority action (e.g., ACT_KILL_PROCESS > ACT_ALLOW) takes precedence
 * according to BPF semantics.
 */
internal object ContainerStateRegistry {
    // INVARIANT: ThreadLocals are INTENTIONALLY not cleared between tasks.
    // Seccomp filters are permanent for the OS thread lifetime.
    // Do NOT add cleanup in task wrappers; it would give a false sense of
    // isolation between tasks on the same thread. See code_issues_backlog.md
    // "Permanent thread pool contamination" for the known limitation and
    // the correct fix strategy (scope checks, not cleanup).

    var threadSyscallActions by threadLocal<Map<Syscall, SeccompAction>> { emptyMap() }
    var threadDefaultAction by threadLocal { SeccompAction.ACT_ALLOW }
    var threadAllowsMmapExec by threadLocal { true }
    var threadAllowsNonThreadClone by threadLocal { true }
    var threadAllowsUnsafePrctl by threadLocal { true }
    var threadAllowedSyscalls by threadLocal<Set<Syscall>?> { null }
    var filterDepth by threadLocal { 0 }
    var threadLandlockAppliedReads by threadLocal<Set<String>?> { null }
    var threadLandlockAppliedWrites by threadLocal<Set<String>?> { null }

    val PROCESS_SYSCALL_ACTIONS: MutableMap<Syscall, SeccompAction> = java.util.concurrent.ConcurrentHashMap()
    val PROCESS_DEFAULT_ACTION = AtomicReference(SeccompAction.ACT_ALLOW)
    val PROCESS_ALLOWS_MMAP_EXEC = AtomicBoolean(true)
    val PROCESS_ALLOWS_NON_THREAD_CLONE = AtomicBoolean(true)
    val PROCESS_ALLOWS_UNSAFE_PRCTL = AtomicBoolean(true)
    val PROCESS_ALLOWED_SYSCALLS = AtomicReference<Set<Syscall>?>(null)

    // Global seccomp filter depth (number of stacked filters applied to the process)
    val PROCESS_FILTER_DEPTH = AtomicInteger(0)
}
