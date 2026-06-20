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
     * ### Classloader reads are handled automatically
     *
     * The supervisor reactor runs [io.mazewall.enforcer.supervisor.JvmStackInspector.isClassloaderActive]
     * **before** calling this method. If the tracee thread holds the JVM `ClassLoader` monitor
     * (i.e., it is in the middle of loading a class), the syscall is immediately allowed and
     * this method is **not called**. Implementations therefore do not need to special-case
     * `.class`/`.jar` files, JVM home paths, or any classloading-related reads — doing so
     * is unnecessary and risks introducing fragile, path-hardcoded bypasses.
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
