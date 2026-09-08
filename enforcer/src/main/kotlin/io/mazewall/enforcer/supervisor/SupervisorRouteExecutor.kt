package io.mazewall.enforcer.supervisor

import io.mazewall.ffi.memory.NativeArena

/** Executes non-terminal supervisor routes behind the typed route context. */
internal interface SupervisorRouteExecutor {
    context(arena: NativeArena) fun injectFd(context: SupervisorRouteContext): Boolean

    context(arena: NativeArena) fun secureExec(
        context: SupervisorRouteContext,
        jvmPath: String?,
    ): Boolean
}
