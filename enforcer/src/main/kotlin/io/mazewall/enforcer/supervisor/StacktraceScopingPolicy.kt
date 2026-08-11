package io.mazewall.enforcer.supervisor

import io.mazewall.core.Syscall
import io.mazewall.core.Tid

public typealias ScopingHandler = (tid: Tid, args: List<Any>, stack: List<StackTraceElement>) -> Boolean

/**
 * Interface allowing developers to authorize system calls based on the JVM calling stack trace.
 */
public interface StacktraceScopingPolicy {
    /**
     * Handlers for supervised system calls.
     *
     * ### Classloader and core JDK reads are handled automatically
     *
     * The supervisor daemon intercepts and automatically handles file reads originating
     * from within the JVM's home directory (`java.home`) via a fast-path. This ensures that
     * core JDK classloading (e.g. loading standard libraries or JVM-internal classes) bypasses
     * policy validation and prevents deadlocks. Implementations therefore do not need to
     * special-case core JVM home paths or standard library classloading reads.
     */
    public val handlers: Map<Syscall, ScopingHandler>
}
