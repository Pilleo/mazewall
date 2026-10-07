package io.mazewall.enforcer.api

import io.mazewall.enforcer.api.execution.InProcessDomain
import io.mazewall.enforcer.api.execution.InvocationEvidence
import io.mazewall.enforcer.api.execution.ManagedRuntime
import io.mazewall.enforcer.internal.execution.RuntimeWorkerContext
import java.util.concurrent.Callable

/** Canonical synchronous materialized-body entry. No Supplier overload or implicit policy union. */
public object Mazewall {
    @JvmStatic
    public fun <T> callContained(
        runtime: ManagedRuntime,
        domain: InProcessDomain,
        action: Callable<T>,
    ): T = runtime.callContained(domain, action)

    /** Read-only observed evidence for supported completion gates such as synchronous transactions. */
    @JvmStatic
    public fun currentInvocationEvidence(): InvocationEvidence? = RuntimeWorkerContext.invocation?.evidence()
}
