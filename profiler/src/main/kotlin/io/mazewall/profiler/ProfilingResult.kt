package io.mazewall.profiler

import io.mazewall.profiler.engine.TraceEvent

/**
 * The result of a [Profiler.profile] invocation.
 *
 * @param value    The value returned by the profiled lambda.
 * @param behavior Everything the profiler observed during the run.
 * @param stackProfile Map of events to their captured stack traces.
 *
 * To compile a policy: result.toPolicy() (refuses incomplete coverage)
 * To get DSL:          result.toDsl() (same coverage gate as toPolicy)
 * To merge runs:       (run1.behavior + run2.behavior).toPolicy(..., coverage, allowIncomplete)
 *
 * Do not call [BillOfBehavior.toDsl] without [ProfilingCoverage]; the default is [ProfilingCoverage.absent]
 * which is incomplete.
 */
data class ProfilingResult<T>(
    val value: T,
    val behavior: BillOfBehavior,
    val stackProfile: Map<TraceEvent, List<Array<StackTraceElement>>>,
    val coverage: ProfilingCoverage = ProfilingCoverage(
        strategy = ProfileStrategy.USER_NOTIF,
        strategyReason = "legacy result without coverage",
        processWide = false,
        ioUring = IoUringVisibility.UNSEEN,
        pathResolution = PathResolutionQuality.NONE,
        stacks = StackAttribution.SKIPPED,
        droppedEvents = 0,
        drainComplete = false,
        environment = ProfileEnvironment("unknown", EbpfLoad.Denied("unprobed")),
        complete = false,
    ),
    val observations: List<ProfileObservation> = emptyList(),
) {
    @JvmOverloads
    fun toPolicy(
        base: io.mazewall.Policy<*, io.mazewall.Uncompiled> = io.mazewall.Policy.PURE_COMPUTE_UNSAFE,
        baseCwd: java.nio.file.Path? = null,
        allowIncomplete: Boolean = false,
    ) = behavior.toPolicy(base, baseCwd, coverage, allowIncomplete)

    @JvmOverloads
    fun toDsl(
        basePolicyName: String = "Policy.PURE_COMPUTE_UNSAFE",
        base: io.mazewall.Policy<*, io.mazewall.Uncompiled> = io.mazewall.Policy.PURE_COMPUTE_UNSAFE,
        baseCwd: java.nio.file.Path? = null,
        allowIncomplete: Boolean = false,
    ) = behavior.toDsl(basePolicyName, base, baseCwd, coverage, allowIncomplete)
}
