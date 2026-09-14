package io.mazewall.enforcer

import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.PolicyScope
import io.mazewall.enforcer.api.StacktraceScopingPolicy
import io.mazewall.enforcer.api.Uncompiled
import java.util.concurrent.ExecutorService

/**
 * Compatibility facade for the relocated [io.mazewall.enforcer.api.ContainedExecutors] entry point.
 */
@Deprecated(
    message = "Use io.mazewall.enforcer.api.ContainedExecutors",
    replaceWith = ReplaceWith(
        "io.mazewall.enforcer.api.ContainedExecutors",
        "io.mazewall.enforcer.api.ContainedExecutors",
    ),
)
public object ContainedExecutors {
    @Deprecated("Use io.mazewall.enforcer.api.ContainedExecutors.installOnCurrentThread")
    public fun installOnCurrentThread(vararg policies: Policy<*, Uncompiled>) {
        io.mazewall.enforcer.api.ContainedExecutors
            .installOnCurrentThread(*policies)
    }

    @Deprecated("Use io.mazewall.enforcer.api.ContainedExecutors.installOnCurrentThread")
    public fun installOnCurrentThread(
        policy: Policy<*, Uncompiled>,
        scopingPolicy: StacktraceScopingPolicy,
    ) {
        io.mazewall.enforcer.api.ContainedExecutors
            .installOnCurrentThread(policy, scopingPolicy)
    }

    @Deprecated("Use io.mazewall.enforcer.api.ContainedExecutors.installOnProcess")
    public fun installOnProcess(vararg policies: Policy<PolicyScope.ProcessWideSafe, Uncompiled>) {
        io.mazewall.enforcer.api.ContainedExecutors
            .installOnProcess(*policies)
    }

    @Deprecated("Use io.mazewall.enforcer.api.ContainedExecutors.wrap")
    public fun wrap(
        delegate: ExecutorService,
        vararg policies: Policy<*, Uncompiled>,
    ): ExecutorService {
        return io.mazewall.enforcer.api.ContainedExecutors
            .wrap(delegate, *policies)
    }

    @Deprecated("Use io.mazewall.enforcer.api.ContainedExecutors.wrap")
    public fun wrap(
        delegate: ExecutorService,
        policy: Policy<*, Uncompiled>,
        scopingPolicy: StacktraceScopingPolicy,
    ): ExecutorService {
        return io.mazewall.enforcer.api.ContainedExecutors
            .wrap(delegate, policy, scopingPolicy)
    }
}
