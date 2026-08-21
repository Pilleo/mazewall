package io.mazewall.enforcer

import io.mazewall.Policy
import java.util.concurrent.Callable

/**
 * Compatibility facade for the relocated [io.mazewall.enforcer.api.SandboxDispatcher].
 */
@Deprecated(
    message = "Use io.mazewall.enforcer.api.SandboxDispatcher",
    replaceWith = ReplaceWith(
        "io.mazewall.enforcer.api.SandboxDispatcher",
        "io.mazewall.enforcer.api.SandboxDispatcher",
    ),
)
public object SandboxDispatcher {
    @Deprecated("Use io.mazewall.enforcer.api.SandboxDispatcher.execute")
    @JvmStatic
    public fun <T> execute(policy: Policy<*, *>, block: Callable<T>): T =
        io.mazewall.enforcer.api.SandboxDispatcher.execute(policy, block)

    @Deprecated("Use io.mazewall.enforcer.api.SandboxDispatcher.executeBlock")
    public fun <T> executeBlock(policy: Policy<*, *>, block: () -> T): T =
        io.mazewall.enforcer.api.SandboxDispatcher.execute(policy, Callable { block() })

    @Deprecated("Use io.mazewall.enforcer.api.SandboxDispatcher.shutdownAll")
    @JvmStatic
    public fun shutdownAll() {
        io.mazewall.enforcer.api.SandboxDispatcher.shutdownAll()
    }
}
