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
class ProfilingResult<T>(
    val value: T,
    val behavior: BillOfBehavior,
    stackProfile: Map<TraceEvent, List<Array<StackTraceElement>>>,
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
    observations: List<ProfileObservation> = emptyList(),
) {
    private val stackProfileStorage = ImmutableStackProfile.copyOf(stackProfile)
    val stackProfile: Map<TraceEvent, List<Array<StackTraceElement>>>
        get() = stackProfileStorage.snapshot()
    val observations: List<ProfileObservation> = java.util.List.copyOf(
        observations.map { observation ->
            when (observation) {
                is ProfileObservation.Syscall -> observation.withPathResolution(observation.pathResolution)
                is ProfileObservation.IoUring -> observation.withPathResolution(observation.pathResolution)
                is ProfileObservation.Connect -> observation.withPathResolution(observation.pathResolution)
            }
        },
    )

    fun copy(
        value: T = this.value,
        behavior: BillOfBehavior = this.behavior,
        stackProfile: Map<TraceEvent, List<Array<StackTraceElement>>> = this.stackProfile,
        coverage: ProfilingCoverage = this.coverage,
        observations: List<ProfileObservation> = this.observations,
    ): ProfilingResult<T> = ProfilingResult(value, behavior, stackProfile, coverage, observations)

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
