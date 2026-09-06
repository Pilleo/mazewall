package io.mazewall.profiler.attribution

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AttributionCollectorTest {
    @Test
    fun `unique edge mode retains only the first resolved stack syscall pair`() {
        val collector = AttributionCollector(TierEEmissionMode.UNIQUE_STACK_SYSCALL)
        val invocationId = InvocationId(101)
        val stackTraceId = StackTraceId(7)
        val definition = StackDefinition(stackTraceId, listOf(frame()), StackCaptureQuality.COMPLETE)
        collector.accept(InvocationDefinition(invocationId, parentInvocationId = null, stackTraceId = stackTraceId))
        collector.accept(definition)

        assertEquals(1, collector.accept(pendingObservation(invocationId)).size)
        assertEquals(emptyList(), collector.accept(pendingObservation(invocationId)))
    }

    @Test
    fun `resolves a syscall when its delayed stack definition arrives`() {
        val collector = AttributionCollector()
        val invocationId = InvocationId(101)
        val stackTraceId = StackTraceId(7)
        val observation = pendingObservation(invocationId)

        collector.accept(InvocationDefinition(invocationId, parentInvocationId = null, stackTraceId = stackTraceId))
        assertEquals(emptyList(), collector.accept(observation))

        val definition = StackDefinition(stackTraceId, listOf(frame()), StackCaptureQuality.COMPLETE)
        assertEquals(
            listOf(ResolvedSyscall(observation, definition, ResolutionStatus.RESOLVED)),
            collector.accept(definition),
        )
        assertEquals(true, collector.integrity.complete)
    }

    @Test
    fun `finishes unknown metadata as unresolved and marks loss incomplete`() {
        val collector = AttributionCollector()
        val observation = pendingObservation(InvocationId(101))

        collector.accept(observation)
        collector.recordLoss(3)

        assertEquals(
            listOf(ResolvedSyscall(observation, null, ResolutionStatus.DICTIONARY_UNRESOLVED)),
            collector.finish(),
        )
        assertEquals(
            SessionIntegrity(observedLosses = 3, attributionFailures = 1),
            collector.integrity,
        )
        assertEquals(CaptureGuarantee.INCOMPLETE, collector.integrity.guarantee)
    }

    private fun pendingObservation(invocationId: InvocationId): SyscallAttribution =
        SyscallAttribution(
            task = TaskIdentity(1, 2, 3),
            taskSequence = 4,
            syscallNumber = 257,
            args = List(6) { 0 },
            invocationId = invocationId,
            attributionStatus = AttributionStatus.PENDING_DICTIONARY,
        )

    private fun frame(): ManagedFrame = ManagedFrame("loader", "app/Service", "open", "()V", 9, ManagedFrameKind.JAVA)
}
