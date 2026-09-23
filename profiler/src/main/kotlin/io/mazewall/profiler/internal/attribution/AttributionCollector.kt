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
    /** Target-process syscalls outside any active native invocation; diagnostic only. */
    val ambientInactiveObservations: Long = 0,
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
    /** Failures while retaining the daemon-owned immutable terminal result. */
    val terminalDeliveryFailures: Long = 0,
    /** The registered producer's terminal artifact was absent or could not be admitted. */
    val artifactAdmissionFailures: Long = 0,
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
    /** Terminal native-to-kernel accounting; diagnostic until a workload supplies an independent denominator. */
    val reconciliation: TierEReconciliation = TierEReconciliation(),
    /** Bounded retained-edge recovery accounting; ring transport verdicts stay authoritative. */
    val recoveryEvidence: CoverageRecoveryEvidence = CoverageRecoveryEvidence(),
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
                terminalDeliveryFailures != 0L ||
                artifactAdmissionFailures != 0L ||
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
            transportComplete = observedLosses == 0L && dictionaryTransport.complete && terminalDeliveryFailures == 0L && artifactAdmissionFailures == 0L,
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

/** Immutable terminal accounting across native boundary admission and kernel observation. */
internal data class TierEReconciliation(
    val admittedInvocations: Long = 0,
    val exactInvocations: Long = 0,
    val failedInvocations: Long = 0,
    val kernelActiveObservations: Long = 0,
    val markerTransitions: Long = 0,
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
    private val readyTransferMode: TierEReadyTransferMode = TierEReadyTransferMode.READ_ONLY,
    private val knownJoinMode: TierEKnownJoinMode = TierEKnownJoinMode.QUEUED_CONTROL,
    private val uniqueEdgeDecisionObserver: ((UniqueEdgeDecision) -> Unit)? = null,
    private val allocationCounter: TierEAllocationCounter? = null,
    private val recoveryMode: CoverageRecoveryMode = CoverageRecoveryMode.DISABLED,
) {
    private val dictionaryJoiner = InvocationDictionaryJoiner(maxPendingRecords, readyTransferMode, allocationCounter)
    private val integrityAccumulator = TierEIntegrityAccumulator()
    private val uniqueEdgeGate = UniqueEdgeGate()
    private val recoveredCoverageLedger = if (recoveryMode == CoverageRecoveryMode.DISABLED) null else RecoveredEdgeLedger()
    private var retainedBudget = TierERetainedStateBudget(maxRetainedBytes)
    private val tokensByResult = java.util.IdentityHashMap<ResolvedSyscall, EmissionToken>()
    private var nextCandidateId: Long = 1
    private var finished = false
    private var pendingCount: Int = 0
    private var pendingObservationBudgetDrops = 0L
    private var pendingObservationQueueDrops = 0L
    private var attributionLifecycleValid = true
    private var resolvedEvidenceAllocatedBytes = 0L
    private var uniqueEdgeGateAllocatedBytes = 0L
    private var uniqueEdgeSuppressionDiagnosticAllocatedBytes = 0L
    private var uniqueEdgeCandidateOwnershipAllocatedBytes = 0L
    private var uniqueEdgeCandidateDiagnosticAllocatedBytes = 0L

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

    /** Benchmark-only allocation attributed to immutable resolved-result construction and append. */
    internal fun resolvedEvidenceAllocatedBytes(): Long = resolvedEvidenceAllocatedBytes

    /** Benchmark-only allocation split for the unique-edge collector hot path. */
    internal fun uniqueEdgeAllocationBreakdown(): UniqueEdgeAllocationBreakdown =
        UniqueEdgeAllocationBreakdown(
            gateCheckBytes = uniqueEdgeGateAllocatedBytes,
            suppressionDiagnosticBytes = uniqueEdgeSuppressionDiagnosticAllocatedBytes,
            candidateOwnershipBytes = uniqueEdgeCandidateOwnershipAllocatedBytes,
            candidateDiagnosticBytes = uniqueEdgeCandidateDiagnosticAllocatedBytes,
        )

    /** Benchmark-only allocation split across delayed dictionary join boundaries. */
    internal fun joinerAllocationBreakdown(): JoinerAllocationBreakdown = dictionaryJoiner.allocationBreakdown()

    /** Separates bounded pending-observation admission failures for benchmark evidence. */
    internal fun pendingObservationDropMetrics(): PendingObservationDropMetrics = PendingObservationDropMetrics(pendingObservationBudgetDrops, pendingObservationQueueDrops)

    /**
     * Validates a retained-edge candidate against the immutable dictionary without
     * touching replay state. The caller owns acknowledgement and coverage accounting.
     */
    internal fun validateCoverageCandidate(
        candidate: CoverageCandidate,
        sessionEpoch: SessionEpoch,
        processIncarnation: ProcessIncarnation,
    ): CoverageRecoveryValidator.Result = CoverageRecoveryValidator.validate(candidate, sessionEpoch, processIncarnation, dictionaryJoiner)

    /** Reports whether the ring path already acknowledged an edge (unique-edge only). */
    internal fun isEdgeAcknowledged(edge: UniqueEdgeGate.Edge): Boolean {
        return recoveredCoverageLedger?.contains(edge) ?: false
    }

    internal fun proposeRecoveredCoverage(edge: UniqueEdgeGate.Edge): EmissionToken? = recoveredCoverageLedger?.propose(edge)

    internal fun acknowledgeRecoveredCoverage(token: EmissionToken) = requireNotNull(recoveredCoverageLedger).acknowledge(token)

    internal fun recoveredCoverageCount(): Int = recoveredCoverageLedger?.acknowledgedCount() ?: 0

    internal fun acknowledgeRetainedRingCoverage(resolved: ResolvedSyscall) {
        val ledger = recoveredCoverageLedger ?: return
        if (resolved.resolutionStatus != ResolutionStatus.RESOLVED) return
        val stack = resolved.stackDefinition ?: return
        ledger.acknowledgeRing(
            UniqueEdgeGate.Edge(stack.stackTraceId, resolved.observation.syscallNumber, resolved.observation.captureFlags),
        )
    }

    /** Publishes bounded recovery accounting without touching event-history verdicts. */
    internal fun recordRecoveryEvidence(evidence: CoverageRecoveryEvidence) {
        integrityAccumulator.recordRecoveryEvidence(evidence)
    }

    /** Immutable IDs whose observations still require a terminal invocation definition. */
    internal fun pendingInvocationIds(): Set<InvocationId> = dictionaryJoiner.pendingInvocationIds()

    public fun accept(definition: StackDefinition): List<ResolvedSyscall> {
        return java.util.List.copyOf(
            dictionaryJoiner.registerStack(definition).flatMap(::resolveReady),
        )
    }

    public fun accept(definition: InvocationDefinition): List<ResolvedSyscall> {
        return java.util.List.copyOf(resolveReady(dictionaryJoiner.registerInvocation(definition)))
    }

    public fun accept(observation: SyscallAttribution): List<ResolvedSyscall> = ArrayList<ResolvedSyscall>(1).also { acceptInto(observation, it) }

    /**
     * Appends resolved evidence directly to a session-owned batch.
     *
     * This is internal because public callers retain the independent-list contract of [accept].
     */
    internal fun acceptInto(
        observation: SyscallAttribution,
        target: MutableCollection<ResolvedSyscall>,
    ) {
        when {
            expectedProcessIncarnation != null && observation.task.processIncarnation != expectedProcessIncarnation -> {
                integrityAccumulator.recordLifecycleInvalidation()
                integrityAccumulator.recordAttributionFailure()
                target += ResolvedSyscall(
                    observation.copy(invocationId = null, attributionStatus = AttributionStatus.STALE_LIFECYCLE_IDENTITY),
                    null,
                    ResolutionStatus.STALE_LIFECYCLE_IDENTITY,
                )
            }
            observation.attributionStatus != AttributionStatus.PENDING_DICTIONARY -> {
                integrityAccumulator.recordAttributionFailure()
                target += ResolvedSyscall(
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
                )
            }
            !attributionLifecycleValid -> {
                integrityAccumulator.recordAttributionFailure()
                target += ResolvedSyscall(
                    observation.copy(invocationId = null, attributionStatus = AttributionStatus.CAUSAL_UNCERTAIN),
                    null,
                    ResolutionStatus.CAUSAL_UNCERTAIN,
                )
            }
            else -> resolveKnownOrQueue(observation, target)
        }
    }

    private fun resolveKnownOrQueue(
        observation: SyscallAttribution,
        target: MutableCollection<ResolvedSyscall>,
    ) {
        if (knownJoinMode == TierEKnownJoinMode.DIRECT_KNOWN && resolveKnownFullStream(observation, target)) return
        if (!retainedBudget.tryReserve(TierERetainedStateBudget.Category.PENDING_OBSERVATIONS, PENDING_OBSERVATION_BYTES)) {
            pendingObservationBudgetDrops++
            recordLoss()
            return
        }
        if (!dictionaryJoiner.enqueue(observation)) {
            retainedBudget.release(TierERetainedStateBudget.Category.PENDING_OBSERVATIONS, PENDING_OBSERVATION_BYTES)
            pendingObservationQueueDrops++
            recordLoss()
            return
        }
        resolveReadyInto(requireNotNull(observation.invocationId) { "pending syscall must have an invocation id" }, target)
    }

    /** Resolves only a complete full-stream definition set; out-of-order and unique-edge paths remain queued. */
    private fun resolveKnownFullStream(
        observation: SyscallAttribution,
        target: MutableCollection<ResolvedSyscall>,
    ): Boolean {
        if (emissionMode != TierEEmissionMode.FULL_STREAM) return false
        val invocation = dictionaryJoiner.knownInvocation(requireNotNull(observation.invocationId)) ?: return false
        val stack = dictionaryJoiner.knownStack(invocation)
        if (stack == null && invocation.captureFailure == null) return false
        if (invocation.captureFailure != null) {
            integrityAccumulator.recordAttributionFailure()
            target += ResolvedSyscall(observation, null, ResolutionStatus.CAPTURE_FAILED)
            return true
        }
        var status = if (requireNotNull(stack).captureQuality == StackCaptureQuality.TRUNCATED) {
            integrityAccumulator.recordAttributionFailure()
            ResolutionStatus.TRUNCATED_STACK
        } else {
            ResolutionStatus.RESOLVED
        }
        if (status == ResolutionStatus.RESOLVED && invocation.executionContext == null && observation.captureFlags and VIRTUAL_THREAD_CAPTURE_FLAG != 0) {
            integrityAccumulator.recordAttributionFailure()
            status = ResolutionStatus.EXECUTION_IDENTITY_UNRESOLVED
        }
        target += ResolvedSyscall(observation, stack, status, invocation.executionContext)
        return true
    }

    public fun recordLoss(count: Long = 1) {
        require(count > 0) { "loss count must be positive" }
        integrityAccumulator.recordLoss(count)
    }

    /** Records an attempt outside the selected capture path; it is never an exact event. */
    public fun recordOutOfDomainAttempt(count: Long = 1) {
        integrityAccumulator.recordOutOfDomainAttempt(count)
    }

    /** Records target-process activity outside an active Tier E native boundary. */
    public fun recordAmbientInactive(count: Long = 1) {
        require(count > 0) { "ambient inactive count must be positive" }
        integrityAccumulator.recordAmbientInactive(count)
    }

    /** Records immutable terminal admission/observation accounting without changing completeness. */
    public fun recordReconciliation(reconciliation: TierEReconciliation) {
        integrityAccumulator.recordReconciliation(reconciliation)
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
        val entry = tokensByResult.entries.firstOrNull { it.value === token }
        require(entry != null) {
            "emission token is stale, duplicated, or foreign"
        }
        val acknowledged = entry.key
        tokensByResult.remove(acknowledged)
        retainedBudget.release(TierERetainedStateBudget.Category.EMISSION_TOKENS, EMISSION_TOKEN_BYTES)
        uniqueEdgeGate.acknowledge(token.edge)
        uniqueEdgeDecisionObserver?.invoke(UniqueEdgeDecision(UniqueEdgeDecision.Kind.ACKNOWLEDGED, acknowledged))
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

    private fun resolveReady(invocationId: InvocationId): List<ResolvedSyscall> = ArrayList<ResolvedSyscall>().also { resolveReadyInto(invocationId, it) }

    private fun resolveReadyInto(
        invocationId: InvocationId,
        target: MutableCollection<ResolvedSyscall>,
    ) {
        val ready = dictionaryJoiner.takeReady(invocationId) ?: return
        val invocation = ready.invocation
        val pending = ready.observations
        retainedBudget.release(
            TierERetainedStateBudget.Category.PENDING_OBSERVATIONS,
            pending.size.toLong() * PENDING_OBSERVATION_BYTES,
        )
        if (invocation.captureFailure != null) {
            integrityAccumulator.recordAttributionFailure(pending.size.toLong())
            for (observation in pending) {
                target += ResolvedSyscall(observation, null, ResolutionStatus.CAPTURE_FAILED)
            }
            return
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
        when (emissionMode) {
            TierEEmissionMode.FULL_STREAM -> appendFullStream(pending, stack, status, invocation, target)
            TierEEmissionMode.UNIQUE_STACK_SYSCALL -> appendUniqueEdges(pending, stack, status, invocation, target)
        }
    }

    /**
     * Full-stream retains every resolved observation. It deliberately avoids allocating a
     * unique-edge key or a filtering lambda on the collector's steady-state hot path.
     */
    private fun appendFullStream(
        pending: List<SyscallAttribution>,
        stack: StackDefinition,
        status: ResolutionStatus,
        invocation: InvocationDefinition,
        target: MutableCollection<ResolvedSyscall>,
    ) {
        for (observation in pending) {
            appendResolved(target, observation, stack, status, invocation.executionContext)
        }
    }

    private fun appendUniqueEdges(
        pending: List<SyscallAttribution>,
        stack: StackDefinition,
        status: ResolutionStatus,
        invocation: InvocationDefinition,
        target: MutableCollection<ResolvedSyscall>,
    ) {
        for (observation in pending) {
            appendUniqueEdge(observation, stack, status, invocation, target)
        }
    }

    private fun appendUniqueEdge(
        observation: SyscallAttribution,
        stack: StackDefinition,
        status: ResolutionStatus,
        invocation: InvocationDefinition,
        target: MutableCollection<ResolvedSyscall>,
    ) {
            val gateAllocationBefore = allocationBefore()
            val edge = UniqueEdgeGate.Edge(stack.stackTraceId, observation.syscallNumber, observation.captureFlags)
            val canEmit = uniqueEdgeGate.canEmit(edge)
            recordAllocation(gateAllocationBefore) { uniqueEdgeGateAllocatedBytes += it }
            if (status == ResolutionStatus.RESOLVED && !canEmit) {
                uniqueEdgeDecisionObserver?.let { observer ->
                    val diagnosticAllocationBefore = allocationBefore()
                    observer(
                        UniqueEdgeDecision(
                            UniqueEdgeDecision.Kind.SUPPRESSED,
                            ResolvedSyscall(observation, stack, status, invocation.executionContext),
                        ),
                    )
                    recordAllocation(diagnosticAllocationBefore) { uniqueEdgeSuppressionDiagnosticAllocatedBytes += it }
                }
            } else {
                val resolved = createResolved(observation, stack, status, invocation.executionContext)
                if (status == ResolutionStatus.RESOLVED && !finished) {
                    if (!retainedBudget.tryReserve(TierERetainedStateBudget.Category.EMISSION_TOKENS, EMISSION_TOKEN_BYTES)) {
                        integrityAccumulator.recordAttributionFailure()
                        return
                    }
                    val ownershipAllocationBefore = allocationBefore()
                    val candidateId = nextCandidateId++
                    val token = EmissionToken(candidateId, edge)
                    tokensByResult[resolved] = token
                    recordAllocation(ownershipAllocationBefore) { uniqueEdgeCandidateOwnershipAllocatedBytes += it }
                    uniqueEdgeDecisionObserver?.let { observer ->
                        val diagnosticAllocationBefore = allocationBefore()
                        observer(
                            UniqueEdgeDecision(
                                UniqueEdgeDecision.Kind.CANDIDATE,
                                resolved,
                            ),
                        )
                        recordAllocation(diagnosticAllocationBefore) { uniqueEdgeCandidateDiagnosticAllocatedBytes += it }
                    }
                }
                target += resolved
            }
    }

    private fun appendResolved(
        target: MutableCollection<ResolvedSyscall>,
        observation: SyscallAttribution,
        stack: StackDefinition?,
        status: ResolutionStatus,
        executionContext: ExecutionContext? = null,
    ) {
        val before = allocationBefore()
        target += ResolvedSyscall(observation, stack, status, executionContext)
        recordResolvedAllocation(before)
    }

    private fun createResolved(
        observation: SyscallAttribution,
        stack: StackDefinition?,
        status: ResolutionStatus,
        executionContext: ExecutionContext? = null,
    ): ResolvedSyscall {
        val before = allocationBefore()
        return ResolvedSyscall(observation, stack, status, executionContext).also { recordResolvedAllocation(before) }
    }

    private fun allocationBefore(): Long = allocationCounter?.currentThreadAllocatedBytes() ?: TierEAllocationCounters.UNAVAILABLE

    private fun recordResolvedAllocation(before: Long) {
        recordAllocation(before) { resolvedEvidenceAllocatedBytes += it }
    }

    private inline fun recordAllocation(
        before: Long,
        record: (Long) -> Unit,
    ) {
        if (before == TierEAllocationCounters.UNAVAILABLE) return
        val after = allocationBefore()
        if (after == TierEAllocationCounters.UNAVAILABLE) return
        if (after >= before) record(after - before)
    }

    private companion object {
        const val VIRTUAL_THREAD_CAPTURE_FLAG = 1
        const val PENDING_OBSERVATION_BYTES = 128L
        const val EMISSION_TOKEN_BYTES = 64L
    }
}

/** Benchmark-only non-overlapping allocation buckets for unique-edge emission. */
internal data class UniqueEdgeAllocationBreakdown(
    val gateCheckBytes: Long = 0,
    val suppressionDiagnosticBytes: Long = 0,
    val candidateOwnershipBytes: Long = 0,
    val candidateDiagnosticBytes: Long = 0,
)

/** Benchmark evidence for the two bounded admission stages preceding delayed dictionary joins. */
internal data class PendingObservationDropMetrics(
    val budgetDrops: Long,
    val queueDrops: Long,
)

/** Benchmark-only observation of collector-owned unique-edge delivery decisions. */
internal data class UniqueEdgeDecision(
    val kind: Kind,
    val syscall: ResolvedSyscall,
) {
    internal enum class Kind {
        CANDIDATE,
        ACKNOWLEDGED,
        SUPPRESSED,
    }
}
