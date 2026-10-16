package io.mazewall.enforcer.api.execution

/** Explicit trusted application capture on the invoking caller, before admission; never a ThreadLocal scan. */
public fun interface InvocationContextBridge {
    public fun capture(): InvocationContextSnapshot
}
