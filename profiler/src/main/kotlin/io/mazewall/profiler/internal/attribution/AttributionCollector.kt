package io.mazewall.profiler.internal.attribution

import io.mazewall.profiler.tierE.TierERetainedStateBudget

/** Opaque session-local identity of the Java execution that entered an invocation. */
@JvmInline
internal value class ExecutionId(
    public val value: Long,
) {
    init {
        require(value > 0) { "execution id must be positive" }
    }
}

/** Whether a logical execution is a traditional platform thread or a Loom virtual thread. */
internal enum class ExecutionKind {
    PLATFORM,
    VIRTUAL,
}

/**
 * Logical Java execution identity, intentionally separate from the carrier task in a syscall event.
 *
 * The ID is allocated by the agent for this profile session and is not a Java thread ID or name.
 */
internal data class ExecutionContext(
    val executionId: ExecutionId,
    val kind: ExecutionKind,
)

/** Native-agent definition of the stack captured for one invocation. */
internal data class InvocationDefinition(
    val invocationId: InvocationId,
    val parentInvocationId: InvocationId?,
    val stackTraceId: StackTraceId?,
    val captureFailure: CaptureFailure? = null,
    val executionContext: ExecutionContext? = null,
) {
    init {
        require((stackTraceId == null) == (captureFailure != null)) {
            "an invocation has either a stack id or an explicit capture failure"
        }
    }
}

internal enum class CaptureFailure {
    STACK_WALK_FAILED,
    STACK_TRUNCATED_REJECTED,
    UNSUPPORTED_EXECUTION,
    EXECUTION_IDENTITY_CHANGED,
}

/**
 * Declares the kernel observation boundary used to collect syscall events.
 *
 * - [SYS_ENTER_TRACEPOINT]: Events are captured via the `raw_tracepoint/sys_enter` hook.
 *   Only syscalls that passed all pre-execution gates (including seccomp) and were
 *   dispatched by the kernel enter this scope. Seccomp-denied or unprobed syscalls are
 *   NOT observed and are therefore outside the reported corpus.
 * - [UNKNOWN]: Capture domain was not declared by the collection session.
 */
internal enum class ObservationScope {
    SYS_ENTER_TRACEPOINT,
    UNKNOWN,
}

internal enum class ResolutionStatus {
    RESOLVED,
    DICTIONARY_UNRESOLVED,
    CAPTURE_FAILED,
    NO_ACTIVE_INVOCATION,
    INVALID_KERNEL_ATTRIBUTION,
    TRUNCATED_STACK,
    EXECUTION_IDENTITY_UNRESOLVED,
    CAUSAL_UNCERTAIN,
    STALE_LIFECYCLE_IDENTITY,
}

/** A syscall after its invocation and stack references have been resolved, or explicitly failed. */
internal data class ResolvedSyscall(
    val observation: SyscallAttribution,
    val stackDefinition: StackDefinition?,
    val resolutionStatus: ResolutionStatus,
    val executionContext: ExecutionContext? = null,
)

/** Session-level evidence that collection was complete enough for the claimed output. */
internal data class SessionIntegrity(
    val observedLosses: Long = 0,
    val attributionFailures: Long = 0,
    /** Syscall attempts known to fall outside the selected observation path. */
    val outOfDomainAttempts: Long = 0,
    val lifecycleInvalidations: Long = 0,
    val emissionMode: TierEEmissionMode = TierEEmissionMode.FULL_STREAM,
    /** Native proxy intervals entered by virtual threads; these pin the carrier by JVM contract. */
    val virtualPinnedScopes: Long = 0,
    /** Sum of measured virtual native-proxy duration, for operational overhead reporting. */
    val virtualPinnedNanos: Long = 0,
    /** Transactional dictionary delivery evidence. */
    val dictionaryTransport: DictionaryTransportEvidence = DictionaryTransportEvidence(),
    /**
     * Kernel observation boundary used to capture syscall events for this session.
     * [ObservationScope.SYS_ENTER_TRACEPOINT] means only post-seccomp dispatched syscalls
     * are observed; seccomp-denied and unprobed attempts are outside this corpus.
     */
    val captureDomain: ObservationScope = ObservationScope.SYS_ENTER_TRACEPOINT,
    /** Same-ID definition collisions observed during live replay. */
    val dictionaryIntegrityFailures: Long = 0,
    /** Publication cost and retained-buffer accounting; independent of completeness. */
    val publicationMetrics: SnapshotPublicationMetrics = SnapshotPublicationMetrics(),
) {
    public val evidenceIntegrity: EvidenceIntegrity
        get() = when {
            captureDomain == ObservationScope.UNKNOWN -> EvidenceIntegrity.UNVERIFIED
            guarantee == CaptureGuarantee.INCOMPLETE -> EvidenceIntegrity.INCOMPLETE
            else -> EvidenceIntegrity.COMPLETE
        }

    public val performanceGate: PerformanceGateStatus = PerformanceGateStatus.NOT_MEASURED

    public val policyEligibility: Boolean = false

    public val guarantee: CaptureGuarantee
        get() = when {
            observedLosses != 0L ||
                attributionFailures != 0L ||
                outOfDomainAttempts != 0L ||
                lifecycleInvalidations != 0L ||
                dictionaryIntegrityFailures != 0L ||
                !dictionaryTransport.complete -> CaptureGuarantee.INCOMPLETE
            emissionMode == TierEEmissionMode.FULL_STREAM -> CaptureGuarantee.FULL_STREAM
            else -> CaptureGuarantee.UNIQUE_EDGES
        }

    /** True only when every syscall was retained, not merely every unique edge. */
    public val complete: Boolean
        get() = guarantee == CaptureGuarantee.FULL_STREAM

    /** True when the selected stream/coverage contract completed without failure. */
    public val coverageComplete: Boolean
        get() = guarantee != CaptureGuarantee.INCOMPLETE

    /** Independent machine-readable verdict dimensions for post-run diagnostics. */
    public val verdictDimensions: TierEVerdictDimensions
        get() = TierEVerdictDimensions(
            captureDomainValid = captureDomain == ObservationScope.SYS_ENTER_TRACEPOINT && outOfDomainAttempts == 0L,
            transportComplete = dictionaryTransport.complete,
            attributionCoverageValid = observedLosses == 0L && attributionFailures == 0L,
            dictionaryIntegrityValid = dictionaryIntegrityFailures == 0L && dictionaryTransport.complete,
            lifecycleValid = lifecycleInvalidations == 0L,
        )
}

internal data class TierEVerdictDimensions(
    public val captureDomainValid: Boolean,
    public val transportComplete: Boolean,
    public val attributionCoverageValid: Boolean,
    public val dictionaryIntegrityValid: Boolean,
    public val lifecycleValid: Boolean,
)

internal enum class EvidenceIntegrity {
    COMPLETE,
    INCOMPLETE,
    UNVERIFIED,
}

internal enum class PerformanceGateStatus {
    PASSED,
    FAILED,
    NOT_MEASURED,
}

/**
 * Joins kernel syscall observations with delayed agent definitions without timestamp matching.
 *
 * A syscall is held only by its explicit invocation id. At session finish, unresolved entries
 * become explicit failed results; they are never assigned an older or neighbouring stack.
 */
internal class AttributionCollector(
    private val emissionMode: TierEEmissionMode = TierEEmissionMode.FULL_STREAM,
    private val maxPendingRecords: Int = TierEOptions.DEFAULT_RECORD_LIMIT,
    private val captureDomain: ObservationScope = ObservationScope.SYS_ENTER_TRACEPOINT,
    private val expectedProcessIncarnation: ProcessIncarnationId? = null,
    maxRetainedBytes: Long = TierERetainedStateBudget.DEFAULT_LIMIT_BYTES,
) {
    private val dictionaryJoiner = InvocationDictionaryJoiner(maxPendingRecords)
    private val integrityAccumulator = TierEIntegrityAccumulator()
    private val uniqueEdgeGate = UniqueEdgeGate()
    private var retainedBudget = TierERetainedStateBudget(maxRetainedBytes)
    private val tokensByResult = java.util.IdentityHashMap<ResolvedSyscall, EmissionToken>()
    private var nextCandidateId: Long = 1
    private var finished = false
    private var pendingCount: Int = 0
    private var attributionLifecycleValid = true

    init {
        require(maxPendingRecords > 0) { "maxPendingRecords must be positive" }
    }

    /** Shares the session budget without widening the public collector API. */
    @Synchronized
    internal fun attachRetainedBudget(budget: TierERetainedStateBudget) {
        check(pendingCount == 0) { "retained-state budget must be attached before observations" }
        check(retainedBudget.usedBytes() == 0L) { "retained-state budget is already in use" }
        retainedBudget = budget
    }

    public val integrity: SessionIntegrity
        get() = integrityAccumulator.snapshot(emissionMode, captureDomain)

    public fun accept(definition: StackDefinition): List<ResolvedSyscall> {
        return java.util.List.copyOf(
            dictionaryJoiner.registerStack(definition).flatMap(::resolveReady),
        )
    }

    public fun accept(definition: InvocationDefinition): List<ResolvedSyscall> {
        return java.util.List.copyOf(resolveReady(dictionaryJoiner.registerInvocation(definition)))
    }

    public fun accept(observation: SyscallAttribution): List<ResolvedSyscall> {
        return when {
            expectedProcessIncarnation != null && observation.task.processIncarnation != expectedProcessIncarnation -> {
                integrityAccumulator.recordLifecycleInvalidation()
                integrityAccumulator.recordAttributionFailure()
                listOf(
                ResolvedSyscall(
                    observation.copy(invocationId = null, attributionStatus = AttributionStatus.STALE_LIFECYCLE_IDENTITY),
                    null,
                    ResolutionStatus.STALE_LIFECYCLE_IDENTITY,
                ),
            )
            }
            observation.attributionStatus != AttributionStatus.PENDING_DICTIONARY -> {
                integrityAccumulator.recordAttributionFailure()
                listOf(
                ResolvedSyscall(
                    observation,
                    null,
                    when (observation.attributionStatus) {
                        AttributionStatus.NO_ACTIVE_INVOCATION -> ResolutionStatus.NO_ACTIVE_INVOCATION
                        AttributionStatus.CAUSAL_UNCERTAIN -> ResolutionStatus.CAUSAL_UNCERTAIN
                        AttributionStatus.STALE_LIFECYCLE_IDENTITY -> ResolutionStatus.STALE_LIFECYCLE_IDENTITY
                        AttributionStatus.SEQUENCE_GAP,
                        AttributionStatus.ACTIVATION_MISMATCH,
                        AttributionStatus.KERNEL_CAPTURE_FAILED,
                        AttributionStatus.PENDING_DICTIONARY,
                        -> ResolutionStatus.INVALID_KERNEL_ATTRIBUTION
                    },
                ),
            )
            }
            !attributionLifecycleValid -> {
                integrityAccumulator.recordAttributionFailure()
                listOf(
                ResolvedSyscall(
                    observation.copy(invocationId = null, attributionStatus = AttributionStatus.CAUSAL_UNCERTAIN),
                    null,
                    ResolutionStatus.CAUSAL_UNCERTAIN,
                ),
            )
            }
            !retainedBudget.tryReserve(TierERetainedStateBudget.Category.PENDING_OBSERVATIONS, PENDING_OBSERVATION_BYTES) -> {
                recordLoss()
                emptyList()
            }
            !dictionaryJoiner.enqueue(observation) -> {
                retainedBudget.release(TierERetainedStateBudget.Category.PENDING_OBSERVATIONS, PENDING_OBSERVATION_BYTES)
                recordLoss()
                emptyList()
            }
            else -> {
                val invocationId = requireNotNull(observation.invocationId) { "pending syscall must have an invocation id" }
                resolveReady(invocationId)
            }
        }
    }

    public fun recordLoss(count: Long = 1) {
        require(count > 0) { "loss count must be positive" }
        integrityAccumulator.recordLoss(count)
    }

    /** Records an attempt outside the selected capture path; it is never an exact event. */
    public fun recordOutOfDomainAttempt(count: Long = 1) {
        integrityAccumulator.recordOutOfDomainAttempt(count)
    }

    public fun recordCaptureDomainEvent(event: CaptureDomainEvent) {
        when (event.classification) {
            CaptureDomainClassification.OBSERVED_SYS_ENTER -> Unit
            CaptureDomainClassification.ATTEMPT_MISSED -> recordOutOfDomainAttempt(event.count)
        }
    }

    /** Invalidates exact attribution until a validated marker re-entry is observed. */
    public fun invalidateAttribution() {
        if (attributionLifecycleValid) {
            integrityAccumulator.recordLifecycleInvalidation()
            integrityAccumulator.recordAttributionFailure()
        }
        attributionLifecycleValid = false
    }

    /** Reopens exact attribution only after a validated new marker boundary. */
    public fun validateAttributionReentry() {
        check(!finished) { "collector is finished" }
        attributionLifecycleValid = true
    }

    /** Records terminal dictionary transport evidence without repairing incomplete attribution. */
    public fun recordDictionaryTransport(evidence: DictionaryTransportEvidence) {
        integrityAccumulator.recordDictionaryTransport(evidence)
    }

    /**
     * Records dropped stack dictionary entries from a [StackDictionaryStreamEncoder].
     *
     * Each dropped entry means one or more pending attributions can never be resolved;
     * they will surface as [ResolutionStatus.DICTIONARY_UNRESOLVED] at [finish] time,
     * and the session [integrity] will reflect an incomplete guarantee.
     */
    public fun recordDictionaryDrops(count: Long) {
        require(count > 0) { "dictionary drop count must be positive" }
        integrityAccumulator.recordLoss(count)
    }

    /** Records a same-ID/different-content definition collision as terminal dictionary evidence. */
    public fun recordDictionaryConflict() {
        integrityAccumulator.recordDictionaryIntegrityFailure()
    }

    /** Adds terminal agent telemetry; it is diagnostic and never repairs attribution completeness. */
    public fun recordVirtualPinnedIntervals(
        scopes: Long,
        nanos: Long,
    ) {
        require(scopes >= 0 && nanos >= 0) { "virtual pin telemetry must be non-negative" }
        integrityAccumulator.recordVirtualPinnedIntervals(scopes, nanos)
    }

    /**
     * Confirms that a resolved record reached its downstream sink.
     *
     * Unique-edge suppression is committed only after this acknowledgement. If a sink fails,
     * the caller may submit the same observation again and receive another emission candidate.
     */
    public fun acknowledgeEmission(resolved: ResolvedSyscall) {
        if (emissionMode != TierEEmissionMode.UNIQUE_STACK_SYSCALL || resolved.resolutionStatus != ResolutionStatus.RESOLVED) return
        val token = tokensByResult[resolved]
            ?: throw IllegalArgumentException("resolved syscall was not returned by this collector")
        acknowledgeEmission(token)
    }

    /** Returns the exact token associated with a collector-returned candidate. */
    public fun emissionToken(resolved: ResolvedSyscall): EmissionToken? = tokensByResult[resolved]

    /** Acknowledges only the exact outstanding candidate represented by [token]. */
    public fun acknowledgeEmission(token: EmissionToken) {
        if (emissionMode != TierEEmissionMode.UNIQUE_STACK_SYSCALL) return
        require(tokensByResult.values.any { it === token }) {
            "emission token is stale, duplicated, or foreign"
        }
        tokensByResult.entries.removeIf { it.value === token }
        retainedBudget.release(TierERetainedStateBudget.Category.EMISSION_TOKENS, EMISSION_TOKEN_BYTES)
        uniqueEdgeGate.acknowledge(token.edge)
    }

    /** Ends the session and makes all retained records explicit, terminal outcomes. */
    public fun finish(): List<ResolvedSyscall> {
        finished = true
        retainedBudget.release(
            TierERetainedStateBudget.Category.EMISSION_TOKENS,
            tokensByResult.size.toLong() * EMISSION_TOKEN_BYTES,
        )
        tokensByResult.clear()
        return java.util.List.copyOf(
            dictionaryJoiner
                .pendingEntries()
            .flatMap { pending ->
                val status = if (pending.invocation?.captureFailure != null) ResolutionStatus.CAPTURE_FAILED else ResolutionStatus.DICTIONARY_UNRESOLVED
                integrityAccumulator.recordAttributionFailure(pending.observations.size.toLong())
                pending.observations.map { ResolvedSyscall(it, null, status) }
            }.also {
                dictionaryJoiner.clearPending()
            },
        )
    }

    private fun resolveReady(invocationId: InvocationId): List<ResolvedSyscall> {
        val ready = dictionaryJoiner.takeReady(invocationId) ?: return emptyList()
        val invocation = ready.invocation
        val pending = ready.observations
        retainedBudget.release(
            TierERetainedStateBudget.Category.PENDING_OBSERVATIONS,
            pending.size.toLong() * PENDING_OBSERVATION_BYTES,
        )
        if (invocation.captureFailure != null) {
            integrityAccumulator.recordAttributionFailure(pending.size.toLong())
            return pending.map { ResolvedSyscall(it, null, ResolutionStatus.CAPTURE_FAILED) }
        }
        val stack = requireNotNull(ready.stack) { "ready invocation must have a stack or capture failure" }
        var status = if (stack.captureQuality == StackCaptureQuality.TRUNCATED) {
            integrityAccumulator.recordAttributionFailure(pending.size.toLong())
            ResolutionStatus.TRUNCATED_STACK
        } else {
            ResolutionStatus.RESOLVED
        }
        if (
            status == ResolutionStatus.RESOLVED &&
            invocation.executionContext == null &&
            pending.any { it.captureFlags and VIRTUAL_THREAD_CAPTURE_FLAG != 0 }
        ) {
            integrityAccumulator.recordAttributionFailure(pending.size.toLong())
            status = ResolutionStatus.EXECUTION_IDENTITY_UNRESOLVED
        }
        return pending.mapNotNull { observation ->
            if (
                status == ResolutionStatus.RESOLVED &&
                emissionMode == TierEEmissionMode.UNIQUE_STACK_SYSCALL &&
                !uniqueEdgeGate.canEmit(UniqueEdgeGate.Edge(stack.stackTraceId, observation.syscallNumber, observation.captureFlags))
            ) {
                null
            } else {
                ResolvedSyscall(observation, stack, status, invocation.executionContext).also { resolved ->
                    if (status == ResolutionStatus.RESOLVED && emissionMode == TierEEmissionMode.UNIQUE_STACK_SYSCALL && !finished) {
                        if (!retainedBudget.tryReserve(TierERetainedStateBudget.Category.EMISSION_TOKENS, EMISSION_TOKEN_BYTES)) {
                            integrityAccumulator.recordAttributionFailure()
                            return@mapNotNull null
                        }
                        val candidateId = nextCandidateId++
                        val edge = UniqueEdgeGate.Edge(stack.stackTraceId, observation.syscallNumber, observation.captureFlags)
                        val token = EmissionToken(candidateId, edge)
                        tokensByResult[resolved] = token
                    }
                }
            }
        }
    }

    private companion object {
        const val VIRTUAL_THREAD_CAPTURE_FLAG = 1
        const val PENDING_OBSERVATION_BYTES = 128L
        const val EMISSION_TOKEN_BYTES = 64L
    }
}
