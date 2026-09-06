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
    fun `resolves a syscall with its opaque virtual execution context`() {
        val collector = AttributionCollector()
        val invocationId = InvocationId(101)
        val stackTraceId = StackTraceId(7)
        val execution = ExecutionContext(ExecutionId(42), ExecutionKind.VIRTUAL)
        val observation = pendingObservation(invocationId)
        val stack = StackDefinition(stackTraceId, listOf(frame()), StackCaptureQuality.COMPLETE)

        collector.accept(
            InvocationDefinition(
                invocationId = invocationId,
                parentInvocationId = null,
                stackTraceId = stackTraceId,
                executionContext = execution,
            ),
        )
        collector.accept(stack)

        assertEquals(
            execution,
            collector.accept(observation).single().executionContext,
        )
    }

    @Test
    fun `same canonical stack retains the execution owner of each invocation`() {
        val collector = AttributionCollector(TierEEmissionMode.UNIQUE_STACK_SYSCALL)
        val stackTraceId = StackTraceId(7)
        val first = InvocationId(101)
        val second = InvocationId(202)
        val firstExecution = ExecutionContext(ExecutionId(11), ExecutionKind.VIRTUAL)
        val secondExecution = ExecutionContext(ExecutionId(22), ExecutionKind.VIRTUAL)
        collector.accept(StackDefinition(stackTraceId, listOf(frame()), StackCaptureQuality.COMPLETE))
        collector.accept(InvocationDefinition(first, null, stackTraceId, executionContext = firstExecution))
        collector.accept(InvocationDefinition(second, null, stackTraceId, executionContext = secondExecution))

        assertEquals(firstExecution, collector.accept(pendingObservation(first)).single().executionContext)
        assertEquals(secondExecution, collector.accept(pendingObservation(second).copy(syscallNumber = 0)).single().executionContext)
    }

    @Test
    fun `legacy virtual observation without execution identity is incomplete`() {
        val collector = AttributionCollector()
        val invocation = InvocationId(101)
        val stack = StackDefinition(StackTraceId(7), listOf(frame()), StackCaptureQuality.COMPLETE)
        collector.accept(InvocationDefinition(invocation, null, stack.stackTraceId))
        collector.accept(stack)

        val resolved = collector.accept(pendingObservation(invocation).copy(captureFlags = 1)).single()

        assertEquals(ResolutionStatus.EXECUTION_IDENTITY_UNRESOLVED, resolved.resolutionStatus)
        assertEquals(CaptureGuarantee.INCOMPLETE, collector.integrity.guarantee)
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

    @Test
    fun `reports virtual native proxy pin telemetry without changing completeness`() {
        val collector = AttributionCollector()

        collector.recordVirtualPinnedIntervals(scopes = 2, nanos = 17)

        assertEquals(2, collector.integrity.virtualPinnedScopes)
        assertEquals(17, collector.integrity.virtualPinnedNanos)
        assertEquals(CaptureGuarantee.FULL_STREAM, collector.integrity.guarantee)
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
