package io.mazewall.enforcer.api.execution

/** Bounded drain reports physical worker retirement and owned cleanup, not merely result delivery. */
public sealed interface DrainReport {
    public data object Complete : DrainReport

    public data class Incomplete(
        public val unfinishedInvocations: Int,
        public val liveWorkers: Int,
        public val cleanupFailure: Throwable? = null,
    ) : DrainReport
}

/** The runtime remains non-admitting; callers can drain again after live work actually ends. */
public class IncompleteDrainException(
    public val report: DrainReport.Incomplete,
) : IllegalStateException("Owned runtime could not finish bounded drain: $report", report.cleanupFailure)
