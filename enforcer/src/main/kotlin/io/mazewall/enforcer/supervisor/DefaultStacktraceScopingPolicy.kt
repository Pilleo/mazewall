package io.mazewall.enforcer.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.state.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.*

import io.mazewall.core.Syscall
import io.mazewall.core.Tid

/**
 * Default StacktraceScopingPolicy that has no handlers.
 */
public object DefaultStacktraceScopingPolicy : StacktraceScopingPolicy {
    override val handlers: Map<Syscall, ScopingHandler> = emptyMap()
}
