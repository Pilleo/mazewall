package io.mazewall.enforcer.api

import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.PolicyScope
import io.mazewall.enforcer.api.Uncompiled
import java.util.concurrent.ExecutorService

/**
 * An executor whose submitted tasks are installed with [policy] before they run.
 *
 * The scope type is a compile-time guardrail. [ContainedExecutors.wrap] always
 * returns [PolicyScope.ThreadLocalOnly]: filters apply to pool threads, not the
 * process. That is not an ACE boundary for untrusted Java; use
 * [ContainedExecutors.installOnProcess] or a portal worker.
 */
public interface ContainedExecutor<out S : PolicyScope> : ExecutorService {
    public val policy: Policy<S, Uncompiled>
}

internal class ContainedExecutorView<S : PolicyScope>(
    private val delegate: ExecutorService,
    override val policy: Policy<S, Uncompiled>,
) : ContainedExecutor<S>,
    ExecutorService by delegate
