package io.mazewall.profiler.internal.attribution

import io.mazewall.profiler.tierE.TierERetainedStateBudget

/** Native MZSE prefix reader owned by the session's serialized collector thread. */
internal class TierENativeLiveSnapshotConsumer(
    session: SessionEpoch,
    maxDefinitions: Int,
    private val budget: TierERetainedStateBudget,
    private val read: (Long) -> AgentDefinitionFile.Contents?,
    private val acceptStack: (StackDefinition) -> Unit,
    private val acceptInvocation: (InvocationDefinition) -> Unit,
    private val onAgentLoss: (Long) -> Unit,
    private val onFailure: (Exception) -> Unit,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private val admission = TierENativeSnapshotAdmission(session, maxDefinitions)
    private var nextReadNanos = 0L
    private var lastAgentLosses = 0L
    private var failed = false
    private var observed = false

    val publicationCount: Long get() = admission.publicationCount
    val accountedAgentLosses: Long get() = lastAgentLosses

    /** An absent first publication is normal; an absent previously seen prefix is not. */
    @Suppress("TooGenericExceptionCaught")
    fun poll() {
        val now = nowNanos()
        if (failed || now < nextReadNanos) return
        nextReadNanos = Math.addExact(now, POLL_INTERVAL_NANOS)
        try {
            val maxBytes = budget.remainingBytes() / COPIES_AT_PEAK
            check(maxBytes > 0) { "no retention available for native live snapshot" }
            val contents = read(maxBytes)
            if (contents == null) {
                check(!observed) { "native live snapshot disappeared after admission" }
            } else {
                observed = true
                admit(contents)
            }
        } catch (failure: Exception) {
            failed = true
            onFailure(failure)
        }
    }

    private fun admit(contents: AgentDefinitionFile.Contents) {
        val imageBytes = Math.multiplyExact(contents.sourceByteLength, COPIES_AT_PEAK)
        check(budget.tryReserve(TierERetainedStateBudget.Category.SNAPSHOT_BUFFERS, imageBytes)) {
            "native live snapshot exceeded collector retention"
        }
        try {
            val prepared = admission.prepare(contents) ?: return
            check(contents.agentLosses >= lastAgentLosses) { "agent loss count regressed in native live snapshot" }
            val retainedBytes = retainedDefinitionBytes(prepared)
            check(budget.tryReserve(TierERetainedStateBudget.Category.DICTIONARY_STAGING, retainedBytes)) {
                "native live definitions exceeded collector retention"
            }
            // The prepared prefix was validated as a whole, including immutable
            // dependencies and prior-ID conflicts, before any collector mutation.
            prepared.stacks.forEach(acceptStack)
            prepared.invocations.forEach(acceptInvocation)
            if (contents.agentLosses > lastAgentLosses) onAgentLoss(contents.agentLosses - lastAgentLosses)
            lastAgentLosses = contents.agentLosses
            admission.commit(prepared)
        } finally {
            budget.release(TierERetainedStateBudget.Category.SNAPSHOT_BUFFERS, imageBytes)
        }
    }

    private fun retainedDefinitionBytes(prepared: TierENativeSnapshotAdmission.Prepared): Long {
        val invocations = Math.multiplyExact(prepared.invocations.size.toLong(), RETAINED_INVOCATION_BYTES)
        return prepared.stacks.fold(invocations) { total, stack ->
            val frames = stack.frames.fold(RETAINED_STACK_BYTES) { bytes, frame ->
                val textChars = frame.classLoaderIdentity.length + frame.className.length + frame.methodName.length + frame.descriptor.length
                Math.addExact(bytes, Math.addExact(RETAINED_FRAME_BYTES, Math.multiplyExact(textChars.toLong(), Char.SIZE_BYTES.toLong())))
            }
            Math.addExact(total, frames)
        }
    }

    private companion object {
        const val COPIES_AT_PEAK = 3L
        const val RETAINED_INVOCATION_BYTES = 128L
        const val RETAINED_STACK_BYTES = 256L
        const val RETAINED_FRAME_BYTES = 96L
        const val POLL_INTERVAL_NANOS = 100_000_000L
    }
}
