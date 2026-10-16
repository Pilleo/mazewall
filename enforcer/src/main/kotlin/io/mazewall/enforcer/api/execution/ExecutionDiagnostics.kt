package io.mazewall.enforcer.api.execution

/** Local owner state, not kernel attestation or authority to start an operation. */
public enum class ExecutionDiagnosticState { UNKNOWN, PREPARING, READY, DRAINING, CLOSED, FAILED }

/** Immutable identity already established by the execution owner. */
public sealed interface ExecutionGeneration {
    public enum class Kind { UNAVAILABLE, IN_PROCESS, PORTAL_INPUTS }

    public val kind: Kind

    public data object Unavailable : ExecutionGeneration {
        override val kind: Kind = Kind.UNAVAILABLE
    }

    public data class InProcess(
        public val generation: Long,
    ) : ExecutionGeneration {
        override val kind: Kind = Kind.IN_PROCESS
    }

    public data class PortalInputs(
        public val inputsSha256: String,
        public val manifestsSha256: String,
        public val runtimeSha256: String,
    ) : ExecutionGeneration {
        override val kind: Kind = Kind.PORTAL_INPUTS
    }
}

/**
 * Producer-observed counters can lag completion callbacks; they never establish retirement.
 * End includes known NotStarted handles. Rejection and completed-handle counts are not partitions.
 */
public data class InvocationDiagnostics(
    public val admitted: Long = 0,
    public val rejected: Long = 0,
    public val cancellationRequests: Long = 0,
    public val delivered: Long = 0,
    public val ended: Long = 0,
)

/** Passive immutable sample. Concurrent fields are observations, not a globally atomic event log. */
public data class ExecutionDiagnostics(
    public val state: ExecutionDiagnosticState,
    public val generation: ExecutionGeneration,
    public val invocations: InvocationDiagnostics,
    public val liveWorkers: Int,
    public val unfinishedInvocations: Int,
    public val retirementComplete: Boolean,
) {
    public companion object {
        @JvmField
        public val UNAVAILABLE: ExecutionDiagnostics = ExecutionDiagnostics(
            ExecutionDiagnosticState.UNKNOWN,
            ExecutionGeneration.Unavailable,
            InvocationDiagnostics(),
            0,
            0,
            false,
        )
    }
}

/** Explicit host-selected operation identity and its existing owner's read-only view. */
public interface ExecutionDiagnosticsSource {
    public fun operationId(): String

    public fun diagnostics(): ExecutionDiagnostics
}
