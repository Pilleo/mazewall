package io.mazewall.enforcer.supervisor

import io.mazewall.core.Syscall
import io.mazewall.core.Tid

/**
 * Default StacktraceScopingPolicy that has no handlers.
 */
public object DefaultStacktraceScopingPolicy : StacktraceScopingPolicy {
    override val handlers: Map<Syscall, ScopingHandler> = emptyMap()
}
