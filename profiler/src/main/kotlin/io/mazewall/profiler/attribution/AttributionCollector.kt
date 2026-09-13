package io.mazewall.profiler.attribution

/** Opaque session-local identity of the Java execution that entered an invocation. */
@JvmInline
public value class ExecutionId(
    public val value: Long,
) {
    init {
        require(value > 0) { "execution id must be positive" }
    }
}

/** Whether a logical execution is a traditional platform thread or a Loom virtual thread. */
public enum class ExecutionKind {
    PLATFORM,
    VIRTUAL,
}

/**
 * Logical Java execution identity, intentionally separate from the carrier task in a syscall event.
 *
 * The ID is allocated by the agent for this profile session and is not a Java thread ID or name.
 */
public data class ExecutionContext(
    val executionId: ExecutionId,
    val kind: ExecutionKind,
)

/** Native-agent definition of the stack captured for one invocation. */
public data class InvocationDefinition(
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

public enum class CaptureFailure {
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
public enum class ObservationScope {
    SYS_ENTER_TRACEPOINT,
    UNKNOWN,
}

public enum class ResolutionStatus {
    RESOLVED,
    DICTIONARY_UNRESOLVED,
    CAPTURE_FAILED,
    NO_ACTIVE_INVOCATION,
    INVALID_KERNEL_ATTRIBUTION,
    TRUNCATED_STACK,
    EXECUTION_IDENTITY_UNRESOLVED,
    CAUSAL_UNCERTAIN,
}

/** A syscall after its invocation and stack references have been resolved, or explicitly failed. */
public data class ResolvedSyscall(
    val observation: SyscallAttribution,
    val stackDefinition: StackDefinition?,
    val resolutionStatus: ResolutionStatus,
    val executionContext: ExecutionContext? = null,
)

/** Session-level evidence that collection was complete enough for the claimed output. */
public data class SessionIntegrity(
    val observedLosses: Long = 0,
    val attributionFailures: Long = 0,
    val emissionMode: TierEEmissionMode = TierEEmissionMode.FULL_STREAM,
    /** Native proxy intervals entered by virtual threads; these pin the carrier by JVM contract. */
    val virtualPinnedScopes: Long = 0,
    /** Sum of measured virtual native-proxy duration, for operational overhead reporting. */
    val virtualPinnedNanos: Long = 0,
    /**
     * Kernel observation boundary used to capture syscall events for this session.
     * [ObservationScope.SYS_ENTER_TRACEPOINT] means only post-seccomp dispatched syscalls
     * are observed; seccomp-denied and unprobed attempts are outside this corpus.
     */
    val captureDomain: ObservationScope = ObservationScope.SYS_ENTER_TRACEPOINT,
) {
    public val guarantee: CaptureGuarantee
        get() = when {
            observedLosses != 0L || attributionFailures != 0L -> CaptureGuarantee.INCOMPLETE
            emissionMode == TierEEmissionMode.FULL_STREAM -> CaptureGuarantee.FULL_STREAM
            else -> CaptureGuarantee.UNIQUE_EDGES
        }

    /** True only when every syscall was retained, not merely every unique edge. */
    public val complete: Boolean
        get() = guarantee == CaptureGuarantee.FULL_STREAM

    /** True when the selected stream/coverage contract completed without failure. */
    public val coverageComplete: Boolean
        get() = guarantee != CaptureGuarantee.INCOMPLETE
}

/**
 * Joins kernel syscall observations with delayed agent definitions without timestamp matching.
 *
 * A syscall is held only by its explicit invocation id. At session finish, unresolved entries
 * become explicit failed results; they are never assigned an older or neighbouring stack.
 */
public class AttributionCollector(
    private val emissionMode: TierEEmissionMode = TierEEmissionMode.FULL_STREAM,
    private val maxPendingRecords: Int = TierEOptions.DEFAULT_RECORD_LIMIT,
    private val captureDomain: ObservationScope = ObservationScope.SYS_ENTER_TRACEPOINT,
) {
    private val stacks = mutableMapOf<StackTraceId, StackDefinition>()
    private val invocations = mutableMapOf<InvocationId, InvocationDefinition>()
    private val pendingByInvocation = mutableMapOf<InvocationId, MutableList<SyscallAttribution>>()
    private val integrityAccumulator = TierEIntegrityAccumulator()
    private val uniqueEdgeGate = UniqueEdgeGate()
    private var pendingCount: Int = 0

    init {
        require(maxPendingRecords > 0) { "maxPendingRecords must be positive" }
    }

    public val integrity: SessionIntegrity
        get() = integrityAccumulator.snapshot(emissionMode, captureDomain)

    public fun accept(definition: StackDefinition): List<ResolvedSyscall> {
        val existing = stacks.putIfAbsent(definition.stackTraceId, definition)
        require(existing == null || existing == definition) { "conflicting stack definition ${definition.stackTraceId.value}" }
        return java.util.List.copyOf(
            invocations.values
            .asSequence()
            .filter { it.stackTraceId == definition.stackTraceId }
            .flatMap { resolveReady(it.invocationId).asSequence() }
            .toList(),
        )
    }

    public fun accept(definition: InvocationDefinition): List<ResolvedSyscall> {
        val existing = invocations.putIfAbsent(definition.invocationId, definition)
        require(existing == null || existing == definition) { "conflicting invocation definition ${definition.invocationId.value}" }
        return java.util.List.copyOf(resolveReady(definition.invocationId))
    }

    public fun accept(observation: SyscallAttribution): List<ResolvedSyscall> {
        if (observation.attributionStatus != AttributionStatus.PENDING_DICTIONARY) {
            integrityAccumulator.recordAttributionFailure()
            return listOf(
                ResolvedSyscall(
                    observation,
                    null,
                    when (observation.attributionStatus) {
                        AttributionStatus.NO_ACTIVE_INVOCATION -> ResolutionStatus.NO_ACTIVE_INVOCATION
                        AttributionStatus.CAUSAL_UNCERTAIN -> ResolutionStatus.CAUSAL_UNCERTAIN
                        AttributionStatus.SEQUENCE_GAP,
                        AttributionStatus.ACTIVATION_MISMATCH,
                        AttributionStatus.KERNEL_CAPTURE_FAILED,
                        AttributionStatus.PENDING_DICTIONARY,
                        -> ResolutionStatus.INVALID_KERNEL_ATTRIBUTION
                    },
                ),
            )
        }
        val invocationId = requireNotNull(observation.invocationId) { "pending syscall must have an invocation id" }
        if (pendingCount == maxPendingRecords) {
            recordLoss()
            return emptyList()
        }
        pendingByInvocation.getOrPut(invocationId, ::mutableListOf).add(observation)
        pendingCount++
        return resolveReady(invocationId)
    }

    public fun recordLoss(count: Long = 1) {
        require(count > 0) { "loss count must be positive" }
        integrityAccumulator.recordLoss(count)
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
        val stack = resolved.stackDefinition ?: return
        uniqueEdgeGate.acknowledge(
            UniqueEdgeGate.Edge(stack.stackTraceId, resolved.observation.syscallNumber, resolved.observation.captureFlags),
        )
    }

    /** Ends the session and makes all retained records explicit, terminal outcomes. */
    public fun finish(): List<ResolvedSyscall> =
        java.util.List.copyOf(
            pendingByInvocation.entries
            .flatMap { (invocationId, observations) ->
            val invocation = invocations[invocationId]
            val status = if (invocation?.captureFailure != null) ResolutionStatus.CAPTURE_FAILED else ResolutionStatus.DICTIONARY_UNRESOLVED
            integrityAccumulator.recordAttributionFailure(observations.size.toLong())
            observations.map { ResolvedSyscall(it, null, status) }
        }.also {
            pendingByInvocation.clear()
            pendingCount = 0
        },
        )

    private fun resolveReady(invocationId: InvocationId): List<ResolvedSyscall> {
        val invocation = invocations[invocationId] ?: return emptyList()
        val pending = pendingByInvocation[invocationId] ?: return emptyList()
        if (invocation.captureFailure != null) {
            pendingByInvocation.remove(invocationId)
            pendingCount -= pending.size
            integrityAccumulator.recordAttributionFailure(pending.size.toLong())
            return pending.map { ResolvedSyscall(it, null, ResolutionStatus.CAPTURE_FAILED) }
        }
        val stack = invocation.stackTraceId?.let(stacks::get)
        if (stack == null) return emptyList()
        pendingByInvocation.remove(invocationId)
        pendingCount -= pending.size
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
                ResolvedSyscall(observation, stack, status, invocation.executionContext)
            }
        }
    }

    private companion object {
        const val VIRTUAL_THREAD_CAPTURE_FLAG = 1
    }
}
