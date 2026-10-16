package io.mazewall.profiler.api

/** Final-result augmentation after recorded-source and mutation-trap audits have completed. */
internal fun <T> ProfilingResult<T>.withCoverageAugmentation(
    recordedLog: Boolean,
    untrappedMutations: List<String>,
): ProfilingResult<T> {
    val recordedCoverage = if (recordedLog) {
        coverage.withWarnings(
            complete = false,
            drainComplete = false,
            warnings = coverage.warnings + "recorded eBPF log is not contemporaneous with this USER_NOTIF run",
        )
    } else {
        coverage
    }
    val auditedCoverage = if (untrappedMutations.isEmpty()) {
        recordedCoverage
    } else {
        recordedCoverage.withWarnings(
            complete = false,
            warnings = recordedCoverage.warnings + "filesystem mutation syscalls were not traced: ${untrappedMutations.joinToString()}",
        )
    }
    return copy(coverage = auditedCoverage)
}
