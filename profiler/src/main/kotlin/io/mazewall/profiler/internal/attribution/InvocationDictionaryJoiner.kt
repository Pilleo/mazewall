package io.mazewall.profiler.internal.attribution

/** Owns delayed invocation observations and their explicit dictionary definitions. */
internal class InvocationDictionaryJoiner(
    private val maxPendingRecords: Int,
    private val readyTransferMode: TierEReadyTransferMode = TierEReadyTransferMode.READ_ONLY,
    private val allocationCounter: TierEAllocationCounter? = null,
) {
    data class Ready(
        val invocation: InvocationDefinition,
        val stack: StackDefinition?,
        val observations: List<SyscallAttribution>,
    )

    data class Pending(
        val invocation: InvocationDefinition?,
        val observations: List<SyscallAttribution>,
    )

    private val stacks = mutableMapOf<StackTraceId, StackDefinition>()
    private val invocations = mutableMapOf<InvocationId, InvocationDefinition>()

    /**
     * A native invocation normally produces one selected syscall. Store that common case directly
     * and promote only a second observation to a mutable bucket; both forms remain collector-owned.
     */
    private val pendingByInvocation = mutableMapOf<InvocationId, Any>()
    private var pendingCount: Int = 0
    private var enqueueAllocatedBytes = 0L
    private var takeReadyAllocatedBytes = 0L

    internal fun allocationBreakdown(): JoinerAllocationBreakdown = JoinerAllocationBreakdown(enqueueAllocatedBytes, takeReadyAllocatedBytes)

    fun registerStack(definition: StackDefinition): List<InvocationId> {
        val existing = stacks.putIfAbsent(definition.stackTraceId, definition)
        require(existing == null || existing == definition) { "conflicting stack definition ${definition.stackTraceId.value}" }
        return invocations.values.filter { it.stackTraceId == definition.stackTraceId }.map { it.invocationId }
    }

    fun registerInvocation(definition: InvocationDefinition): InvocationId {
        val existing = invocations.putIfAbsent(definition.invocationId, definition)
        require(existing == null || existing == definition) { "conflicting invocation definition ${definition.invocationId.value}" }
        return definition.invocationId
    }

    /** Returns an immutable invocation definition already admitted to the dictionary, if any. */
    fun knownInvocation(invocationId: InvocationId): InvocationDefinition? = invocations[invocationId]

    /** Returns the immutable stack definition referenced by an admitted invocation, if present. */
    fun knownStack(invocation: InvocationDefinition): StackDefinition? = invocation.stackTraceId?.let(stacks::get)

    fun enqueue(observation: SyscallAttribution): Boolean {
        if (pendingCount == maxPendingRecords) return false
        val invocationId = requireNotNull(observation.invocationId) { "pending syscall must have an invocation id" }
        val allocationBefore = allocationCounter?.currentThreadAllocatedBytes() ?: TierEAllocationCounters.UNAVAILABLE
        when (val pending = pendingByInvocation[invocationId]) {
            null -> pendingByInvocation[invocationId] = observation
            is SyscallAttribution -> pendingByInvocation[invocationId] = mutableListOf(pending, observation)
            is MutableList<*> -> {
                @Suppress("UNCHECKED_CAST")
                (pending as MutableList<SyscallAttribution>).add(observation)
            }
            else -> error("unsupported pending observation bucket")
        }
        val allocated = allocationDelta(allocationBefore)
        if (allocated != TierEAllocationCounters.UNAVAILABLE) {
            enqueueAllocatedBytes += allocated
        }
        pendingCount++
        return true
    }

    fun takeReady(invocationId: InvocationId): Ready? {
        val invocation = invocations[invocationId] ?: return null
        val observations = pendingByInvocation[invocationId] ?: return null
        val stack = invocation.stackTraceId?.let(stacks::get)
        if (stack == null && invocation.captureFailure == null) return null
        val allocationBefore = allocationCounter?.currentThreadAllocatedBytes() ?: TierEAllocationCounters.UNAVAILABLE
        pendingByInvocation.remove(invocationId)
        pendingCount -= observationCount(observations)
        // Removal detaches this bucket from joiner ownership. The collector only reads it while
        // constructing immutable resolved evidence, so a read-only view avoids a duplicate copy.
        val ready = Ready(invocation, stack, immutableObservations(observations))
        val allocated = allocationDelta(allocationBefore)
        if (allocated != TierEAllocationCounters.UNAVAILABLE) {
            takeReadyAllocatedBytes += allocated
        }
        return ready
    }

    fun pendingEntries(): List<Pending> =
        pendingByInvocation.entries.map { (invocationId, observations) ->
            Pending(invocations[invocationId], immutableObservations(observations))
        }

    fun pendingInvocationIds(): Set<InvocationId> = java.util.Set.copyOf(pendingByInvocation.keys)

    fun clearPending() {
        pendingByInvocation.clear()
        pendingCount = 0
    }

    private fun allocationDelta(before: Long): Long {
        if (before == TierEAllocationCounters.UNAVAILABLE) return TierEAllocationCounters.UNAVAILABLE
        val counter = allocationCounter ?: return TierEAllocationCounters.UNAVAILABLE
        val after = counter.currentThreadAllocatedBytes()
        if (after == TierEAllocationCounters.UNAVAILABLE) return TierEAllocationCounters.UNAVAILABLE
        val delta = after - before
        return if (delta >= 0L) delta else TierEAllocationCounters.UNAVAILABLE
    }

    private fun observationCount(observations: Any): Int = if (observations is SyscallAttribution) 1 else (observations as List<*>).size

    private fun immutableObservations(observations: Any): List<SyscallAttribution> =
        when (readyTransferMode) {
            TierEReadyTransferMode.READ_ONLY -> when (observations) {
                is SyscallAttribution -> java.util.Collections.singletonList(observations)
                is MutableList<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    java.util.Collections.unmodifiableList(observations as MutableList<SyscallAttribution>)
                }
                else -> error("unsupported pending observation bucket")
            }
            TierEReadyTransferMode.COPY_CONTROL -> when (observations) {
                is SyscallAttribution -> listOf(observations)
                is MutableList<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    (observations as List<SyscallAttribution>).toList()
                }
                else -> error("unsupported pending observation bucket")
            }
        }
}

/** Benchmark-only allocation split across delayed dictionary join boundaries. */
internal data class JoinerAllocationBreakdown(
    val enqueueBytes: Long = 0,
    val takeReadyBytes: Long = 0,
)
