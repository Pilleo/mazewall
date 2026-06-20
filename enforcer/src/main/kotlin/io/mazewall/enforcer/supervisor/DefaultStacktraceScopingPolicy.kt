package io.mazewall.enforcer.supervisor

import io.mazewall.core.Syscall
import io.mazewall.core.Tid

/**
 * Default StacktraceScopingPolicy that always returns true, allowing all supervised syscalls.
 */
public object DefaultStacktraceScopingPolicy : StacktraceScopingPolicy {
    override fun authorize(
        tid: Tid,
        syscall: Syscall,
        args: List<Any>,
        stack: List<StackTraceElement>
    ): Boolean = true
}
