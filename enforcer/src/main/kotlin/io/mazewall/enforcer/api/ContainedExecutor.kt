package io.mazewall.enforcer.api

import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.PolicyScope
import io.mazewall.enforcer.api.Uncompiled
import java.util.concurrent.Executor

/**
 * An executor whose submitted tasks are installed with [policy] before they run.
 *
 * The scope type is a compile-time guardrail: it documents whether the policy can
 * safely be installed process-wide or must remain thread-local.
 */
public interface ContainedExecutor<out S : PolicyScope> : Executor {
    public val policy: Policy<S, Uncompiled>
}

internal class ContainedExecutorView<S : PolicyScope>(
    private val delegate: Executor,
    override val policy: Policy<S, Uncompiled>,
) : ContainedExecutor<S> {
    override fun execute(command: Runnable) {
        delegate.execute(command)
    }
}
