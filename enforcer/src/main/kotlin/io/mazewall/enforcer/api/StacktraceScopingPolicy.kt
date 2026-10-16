package io.mazewall.enforcer.api

import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.syscall.Syscall

/** Callback used to authorize a supervised syscall from its JVM stack. */
public typealias ScopingHandler = (tid: Tid, args: List<Any>, stack: List<StackTraceElement>) -> Boolean

/**
 * Application extension point for stack-trace-scoped containment decisions.
 * JVM-home reads required for class loading are handled by the containment runtime.
 */
public interface StacktraceScopingPolicy {
    public val handlers: Map<Syscall, ScopingHandler>
}

/** The policy with no application-defined stack-trace handlers. */
public object DefaultStacktraceScopingPolicy : StacktraceScopingPolicy {
    override val handlers: Map<Syscall, ScopingHandler> = emptyMap()
}
