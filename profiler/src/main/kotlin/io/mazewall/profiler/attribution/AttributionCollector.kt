package io.mazewall.profiler.attribution

/** Native-agent definition of the stack captured for one invocation. */
public data class InvocationDefinition(
    val invocationId: InvocationId,
    val parentInvocationId: InvocationId?,
    val stackTraceId: StackTraceId?,
    val captureFailure: CaptureFailure? = null,
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
}

public enum class ResolutionStatus {
    RESOLVED,
    DICTIONARY_UNRESOLVED,
    CAPTURE_FAILED,
    NO_ACTIVE_INVOCATION,
    INVALID_KERNEL_ATTRIBUTION,
    TRUNCATED_STACK,
}

/** A syscall after its invocation and stack references have been resolved, or explicitly failed. */
public data class ResolvedSyscall(
    val observation: SyscallAttribution,
    val stackDefinition: StackDefinition?,
    val resolutionStatus: ResolutionStatus,
)

/** Session-level evidence that collection was complete enough for the claimed output. */
public data class SessionIntegrity(
    val observedLosses: Long = 0,
    val attributionFailures: Long = 0,
    val emissionMode: TierEEmissionMode = TierEEmissionMode.FULL_STREAM,
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
) {
    private val stacks = mutableMapOf<StackTraceId, StackDefinition>()
    private val invocations = mutableMapOf<InvocationId, InvocationDefinition>()
    private val pendingByInvocation = mutableMapOf<InvocationId, MutableList<SyscallAttribution>>()
    private val emittedCoverageEdges = mutableSetOf<CoverageEdge>()
    private var losses: Long = 0
    private var failures: Long = 0

    public val integrity: SessionIntegrity
        get() = SessionIntegrity(
            observedLosses = losses,
            attributionFailures = failures,
            emissionMode = emissionMode,
        )

    public fun accept(definition: StackDefinition): List<ResolvedSyscall> {
        val existing = stacks.putIfAbsent(definition.stackTraceId, definition)
        require(existing == null || existing == definition) { "conflicting stack definition ${definition.stackTraceId.value}" }
        return invocations.values
            .asSequence()
            .filter { it.stackTraceId == definition.stackTraceId }
            .flatMap { resolveReady(it.invocationId).asSequence() }
            .toList()
    }

    public fun accept(definition: InvocationDefinition): List<ResolvedSyscall> {
        val existing = invocations.putIfAbsent(definition.invocationId, definition)
        require(existing == null || existing == definition) { "conflicting invocation definition ${definition.invocationId.value}" }
        return resolveReady(definition.invocationId)
    }

    public fun accept(observation: SyscallAttribution): List<ResolvedSyscall> {
        if (observation.attributionStatus != AttributionStatus.PENDING_DICTIONARY) {
            failures = Math.addExact(failures, 1)
            return listOf(
                ResolvedSyscall(
                    observation,
                    null,
                    when (observation.attributionStatus) {
                        AttributionStatus.NO_ACTIVE_INVOCATION -> ResolutionStatus.NO_ACTIVE_INVOCATION
                        else -> ResolutionStatus.INVALID_KERNEL_ATTRIBUTION
                    },
                ),
            )
        }
        val invocationId = requireNotNull(observation.invocationId) { "pending syscall must have an invocation id" }
        pendingByInvocation.getOrPut(invocationId, ::mutableListOf).add(observation)
        return resolveReady(invocationId)
    }

    public fun recordLoss(count: Long = 1) {
        require(count > 0) { "loss count must be positive" }
        losses = Math.addExact(losses, count)
    }

    /** Ends the session and makes all retained records explicit, terminal outcomes. */
    public fun finish(): List<ResolvedSyscall> =
        pendingByInvocation.entries.flatMap { (invocationId, observations) ->
            val invocation = invocations[invocationId]
            val status = if (invocation?.captureFailure != null) ResolutionStatus.CAPTURE_FAILED else ResolutionStatus.DICTIONARY_UNRESOLVED
            failures = Math.addExact(failures, observations.size.toLong())
            observations.map { ResolvedSyscall(it, null, status) }
        }.also { pendingByInvocation.clear() }

    private fun resolveReady(invocationId: InvocationId): List<ResolvedSyscall> {
        val invocation = invocations[invocationId] ?: return emptyList()
        val pending = pendingByInvocation[invocationId] ?: return emptyList()
        if (invocation.captureFailure != null) {
            pendingByInvocation.remove(invocationId)
            failures = Math.addExact(failures, pending.size.toLong())
            return pending.map { ResolvedSyscall(it, null, ResolutionStatus.CAPTURE_FAILED) }
        }
        val stack = invocation.stackTraceId?.let(stacks::get)
        if (stack == null) return emptyList()
        pendingByInvocation.remove(invocationId)
        val status = if (stack.captureQuality == StackCaptureQuality.TRUNCATED) {
            failures = Math.addExact(failures, pending.size.toLong())
            ResolutionStatus.TRUNCATED_STACK
        } else {
            ResolutionStatus.RESOLVED
        }
        return pending.mapNotNull { observation ->
            if (
                status == ResolutionStatus.RESOLVED &&
                emissionMode == TierEEmissionMode.UNIQUE_STACK_SYSCALL &&
                !emittedCoverageEdges.add(CoverageEdge(stack.stackTraceId, observation.syscallNumber, observation.captureFlags))
            ) {
                null
            } else {
                ResolvedSyscall(observation, stack, status)
            }
        }
    }

    private data class CoverageEdge(
        val stackTraceId: StackTraceId,
        val syscallNumber: Int,
        val captureFlags: Int,
    )
}
