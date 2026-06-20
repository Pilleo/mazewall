package io.mazewall.enforcer.supervisor

import io.mazewall.core.Syscall
import io.mazewall.core.Tid

/**
 * Interface allowing developers to authorize system calls based on the JVM calling stack trace.
 */
public interface StacktraceScopingPolicy {
    /**
     * Authorizes or denies a system call.
     *
     * @param tid The Thread ID of the blocked thread.
     * @param syscall The system call being invoked.
     * @param args The resolved/raw system call arguments.
     * @param stack The current JVM stack trace of the calling thread.
     * @return true to authorize (allow), false to deny.
     */
    public fun authorize(
        tid: Tid,
        syscall: Syscall,
        args: List<Any>,
        stack: List<StackTraceElement>
    ): Boolean
}
