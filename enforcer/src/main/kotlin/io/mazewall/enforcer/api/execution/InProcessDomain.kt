package io.mazewall.enforcer.api.execution

import io.mazewall.enforcer.api.OperationBinding
import io.mazewall.enforcer.internal.execution.OwnedDomain

/** Opaque fixed-policy generation prepared by its exact runtime owner. */
public class InProcessDomain internal constructor(
    internal val owner: ManagedRuntime,
    internal val resource: OwnedDomain,
    public val operationBinding: OperationBinding? = null,
) {
    public val generation: Long get() = resource.generation
    public val limits: DomainLimits get() = resource.limits
}
