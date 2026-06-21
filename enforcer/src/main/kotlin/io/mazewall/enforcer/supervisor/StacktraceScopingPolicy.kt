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
     * ### Classloader and core JDK reads are handled automatically
     *
     * The supervisor daemon intercepts and automatically handles file reads originating
     * from within the JVM's home directory (`java.home`) via a fast-path. This ensures that
     * core JDK classloading (e.g. loading standard libraries or JVM-internal classes) bypasses
     * policy validation and prevents deadlocks. Implementations therefore do not need to
     * special-case core JVM home paths or standard library classloading reads.
     *
     * @param tid The Thread ID of the blocked thread.
     * @param syscall The system call being invoked.
     * @param args The resolved/raw system call arguments.
     * @param stack The current JVM stack trace of the calling thread.
     * @return `true` to authorize (allow), `false` to deny.
     */
    public fun authorize(
        tid: Tid,
        syscall: Syscall,
        args: List<Any>,
        stack: List<StackTraceElement>
    ): Boolean
}
