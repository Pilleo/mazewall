package io.mazewall.enforcer

import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Internal registry for tracking seccomp and Landlock state applied to the entire process.
 */
internal object ProcessStateRegistry {
    val SYSCALL_ACTIONS: AtomicReference<Map<Syscall, SeccompAction>> = AtomicReference(emptyMap())
    val DEFAULT_ACTION: AtomicReference<SeccompAction> = AtomicReference(SeccompAction.ACT_ALLOW)
    val ALLOWS_MMAP_EXEC: AtomicBoolean = AtomicBoolean(true)
    val ALLOWS_NON_THREAD_CLONE: AtomicBoolean = AtomicBoolean(true)
    val ALLOWS_UNSAFE_PRCTL: AtomicBoolean = AtomicBoolean(true)
    val ALLOWED_SYSCALLS: AtomicReference<Set<Syscall>?> = AtomicReference(null)

    // Global seccomp filter depth (number of stacked filters applied to the process)
    val FILTER_DEPTH: AtomicInteger = AtomicInteger(0)
}

