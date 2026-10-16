package io.mazewall.enforcer.api.execution

/** Finite platform workers and admitted invocations, including queued and still-running work. */
public data class DomainLimits(
    public val workers: Int,
    public val maxAdmissions: Int,
) {
    init {
        require(workers > 0) { "Worker count must be positive" }
        require(maxAdmissions > 0) { "Admission count must be positive" }
    }
}
