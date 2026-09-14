package io.mazewall.profiler.internal.attribution

/** Owns delayed invocation observations and their explicit dictionary definitions. */
internal class InvocationDictionaryJoiner(
    private val maxPendingRecords: Int,
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
    private val pendingByInvocation = mutableMapOf<InvocationId, MutableList<SyscallAttribution>>()
    private var pendingCount: Int = 0

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

    fun enqueue(observation: SyscallAttribution): Boolean {
        if (pendingCount == maxPendingRecords) return false
        val invocationId = requireNotNull(observation.invocationId) { "pending syscall must have an invocation id" }
        pendingByInvocation.getOrPut(invocationId, ::mutableListOf).add(observation)
        pendingCount++
        return true
    }

    fun takeReady(invocationId: InvocationId): Ready? {
        val invocation = invocations[invocationId] ?: return null
        val observations = pendingByInvocation[invocationId] ?: return null
        val stack = invocation.stackTraceId?.let(stacks::get)
        if (stack == null && invocation.captureFailure == null) return null
        pendingByInvocation.remove(invocationId)
        pendingCount -= observations.size
        return Ready(invocation, stack, observations.toList())
    }

    fun pendingEntries(): List<Pending> =
        pendingByInvocation.entries.map { (invocationId, observations) ->
            Pending(invocations[invocationId], observations.toList())
        }

    fun clearPending() {
        pendingByInvocation.clear()
        pendingCount = 0
    }
}
