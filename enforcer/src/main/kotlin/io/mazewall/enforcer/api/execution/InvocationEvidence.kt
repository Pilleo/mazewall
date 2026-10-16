package io.mazewall.enforcer.api.execution

/** Library-observed failure in the current logical operation; not proof of all kernel activity. */
public data class InvocationEvidence(
    public val domainGeneration: Long,
    public val observedFailure: Throwable?,
)

/** An unsupported domain transition is rejected before the requested body can run. */
public class ExecutionTransitionException(
    message: String,
) : IllegalStateException(message)
