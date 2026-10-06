package io.mazewall.profiler.api

import io.mazewall.profiler.api.TraceEvent

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
public class ProfilingResult<T> private constructor(
    public val value: T,
    public val behavior: BillOfBehavior,
    stackProfile: Map<TraceEvent, List<Array<StackTraceElement>>>,
    coverage: ProfilingCoverage,
    observations: List<ProfileObservation>,
    private val structuredOwner: StructuredProfileEvidence?,
) {
    public constructor(
        value: T,
        behavior: BillOfBehavior,
        stackProfile: Map<TraceEvent, List<Array<StackTraceElement>>>,
        coverage: ProfilingCoverage = ProfilingCoverage(
        strategy = ProfileStrategy.USER_NOTIF,
        strategyReason = "legacy result without coverage",
        processWide = false,
        ioUring = IoUringVisibility.UNSEEN,
        pathResolution = PathResolutionQuality.NONE,
        stacks = StackAttribution.SKIPPED,
        droppedEvents = 0,
        drainComplete = false,
        environment = ProfileEnvironment("unknown", EbpfLoad.Denied("unprobed")),
        integrity = ProfileIntegrity(false),
    ),
        observations: List<ProfileObservation> = emptyList(),
    ) : this(value, behavior, stackProfile, coverage, observations, null)

    public val coverage: ProfilingCoverage = if (structuredOwner == null) {
        coverage
    } else {
        coverage.copy(
        strategy = ProfileStrategy.HOTSPOT_AGENTLESS,
        integrity = coverage.integrity.copy(complete = false),
    )
    }

    /** Ordered immutable provider evidence; absent for existing profiling mechanisms. */
    public val structuredEvidence: StructuredProfileEvidence? get() = structuredOwner
    private val stackProfileStorage = ImmutableStackProfile.copyOf(stackProfile)
    public val stackProfile: Map<TraceEvent, List<Array<StackTraceElement>>>
        get() = stackProfileStorage.snapshot()
    public val observations: List<ProfileObservation> = java.util.List.copyOf(
        observations.map { observation ->
            when (observation) {
                is ProfileObservation.Syscall -> observation.withPathResolution(observation.pathResolution)
                is ProfileObservation.IoUring -> observation.withPathResolution(observation.pathResolution)
                is ProfileObservation.Connect -> observation.withPathResolution(observation.pathResolution)
            }
        },
    )

    public fun copy(
        value: T = this.value,
        behavior: BillOfBehavior = this.behavior,
        stackProfile: Map<TraceEvent, List<Array<StackTraceElement>>> = this.stackProfile,
        coverage: ProfilingCoverage = this.coverage,
        observations: List<ProfileObservation> = this.observations,
    ): ProfilingResult<T> = ProfilingResult(value, behavior, stackProfile, coverage, observations, structuredOwner)

    internal fun withStructuredEvidence(evidence: StructuredProfileEvidence): ProfilingResult<T> = ProfilingResult(value, behavior, stackProfile, coverage, observations, evidence)

    internal fun <R> withValue(value: R): ProfilingResult<R> = ProfilingResult(value, behavior, stackProfile, coverage, observations, structuredOwner)

    /** Serializes behavior/coverage, never [value] or stacks. Save typed diagnostics via [StructuredProfileEvidence.toJson]. */
    public fun toProfileDocumentJson(): String = ProfileDocument.from(this).toJson()

    @JvmOverloads
    public fun toPolicy(
        base: io.mazewall.enforcer.api.Policy<*, io.mazewall.enforcer.api.Uncompiled> = io.mazewall.enforcer.api.Policy.PURE_COMPUTE_UNSAFE,
        baseCwd: java.nio.file.Path? = null,
    ): io.mazewall.enforcer.api.Policy<io.mazewall.enforcer.api.PolicyScope.ThreadLocalOnly, io.mazewall.enforcer.api.Uncompiled> = behavior.toPolicy(base, baseCwd, coverage)

    @JvmName("toPolicyAllowingIncomplete")
    internal fun toPolicyAllowingIncomplete(
        base: io.mazewall.enforcer.api.Policy<*, io.mazewall.enforcer.api.Uncompiled> = io.mazewall.enforcer.api.Policy.PURE_COMPUTE_UNSAFE,
        baseCwd: java.nio.file.Path? = null,
    ): io.mazewall.enforcer.api.Policy<io.mazewall.enforcer.api.PolicyScope.ThreadLocalOnly, io.mazewall.enforcer.api.Uncompiled> = behavior.toPolicyAllowingIncomplete(base, baseCwd, coverage)

    @JvmOverloads
    public fun toDsl(
        basePolicyName: String = "Policy.PURE_COMPUTE_UNSAFE",
        base: io.mazewall.enforcer.api.Policy<*, io.mazewall.enforcer.api.Uncompiled> = io.mazewall.enforcer.api.Policy.PURE_COMPUTE_UNSAFE,
        baseCwd: java.nio.file.Path? = null,
    ): String = behavior.toDsl(basePolicyName, base, baseCwd, coverage)

    @JvmName("toDslAllowingIncomplete")
    internal fun toDslAllowingIncomplete(
        basePolicyName: String = "Policy.PURE_COMPUTE_UNSAFE",
        base: io.mazewall.enforcer.api.Policy<*, io.mazewall.enforcer.api.Uncompiled> = io.mazewall.enforcer.api.Policy.PURE_COMPUTE_UNSAFE,
        baseCwd: java.nio.file.Path? = null,
    ): String = behavior.toDslAllowingIncomplete(basePolicyName, base, baseCwd, coverage)

    /**
     * Compiles this profile into policies for [io.mazewall.enforcer.api.ContainedExecutors.installOnDedicatedProcess].
     * Incomplete coverage fails closed; it never produces a worker allow-list.
     * Use this **or** the worker's static fail-closed trio — never `allowIncomplete`.
     */
    @JvmOverloads
    public fun toDedicatedWorkerPolicies(
        runtime: io.mazewall.enforcer.api.RuntimeProfile = io.mazewall.enforcer.api.RuntimeProfile.HOTSPOT_JIT,
    ): Array<io.mazewall.enforcer.api.Policy<*, io.mazewall.enforcer.api.Uncompiled>> {
        val base = io.mazewall.enforcer.api.Policy
            .threadLocalBuilder()
            .base(io.mazewall.enforcer.api.Policy.PURE_COMPUTE_UNSAFE)
            .forRuntime(runtime)
            .build()
        val bob = toPolicy(base = base)
        return arrayOf(
            io.mazewall.enforcer.api.ProcessPolicies
                .workerFilesystem(runtime),
            io.mazewall.enforcer.api.ProcessPolicies
                .denyProcessCreation(runtime),
            io.mazewall.enforcer.api.ProcessPolicies
                .denyNetwork(runtime),
            bob,
        )
    }

    public companion object {
        /**
         * Portal worker opt-in: the document must preserve measured complete
         * coverage. Plain SBoB JSON and legacy attestations are rejected.
         */
        @JvmStatic
        public fun dedicatedWorkerPoliciesFromDocument(json: String): Array<io.mazewall.enforcer.api.Policy<*, io.mazewall.enforcer.api.Uncompiled>> {
            val document = try {
                ProfileDocument.fromJson(json)
            } catch (_: IllegalArgumentException) {
                throw IncompleteProfileException(ProfilingCoverage.absent())
            }
            return ProfilingResult(Unit, document.behavior, emptyMap(), document.coverage).toDedicatedWorkerPolicies()
        }
    }
}
