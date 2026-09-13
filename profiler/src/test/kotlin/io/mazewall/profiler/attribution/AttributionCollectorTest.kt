package io.mazewall.profiler.attribution

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AttributionCollectorTest {
    @Test
    fun `pending overflow drops new observations and makes integrity incomplete`() {
        val collector = AttributionCollector(maxPendingRecords = 1)
        val first = pendingObservation(InvocationId(101))
        val dropped = pendingObservation(InvocationId(102))

        collector.accept(first)
        assertEquals(emptyList(), collector.accept(dropped))

        assertEquals(1, collector.integrity.observedLosses)
        assertEquals(CaptureGuarantee.INCOMPLETE, collector.integrity.guarantee)
        assertEquals(listOf(first), collector.finish().map { it.observation })
    }

    @Test
    fun `unique edge mode retains only the first resolved stack syscall pair`() {
        val collector = AttributionCollector(TierEEmissionMode.UNIQUE_STACK_SYSCALL)
        val invocationId = InvocationId(101)
        val stackTraceId = StackTraceId(7)
        val definition = StackDefinition(stackTraceId, listOf(frame()), StackCaptureQuality.COMPLETE)
        collector.accept(InvocationDefinition(invocationId, parentInvocationId = null, stackTraceId = stackTraceId))
        collector.accept(definition)

        val first = collector.accept(pendingObservation(invocationId))
        assertEquals(1, first.size)
        collector.acknowledgeEmission(first.single())
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

    @Test
    fun `causal uncertain observation maps to causal uncertain resolution and is never resolved`() {
        val collector = AttributionCollector()
        val observation = causalUncertainObservation()

        val results = collector.accept(observation)

        assertEquals(1, results.size)
        val resolved = results.single()
        assertEquals(ResolutionStatus.CAUSAL_UNCERTAIN, resolved.resolutionStatus)
        assertEquals(null, resolved.stackDefinition)
        assertEquals(observation, resolved.observation)
        // attributionFailures must be incremented — fail-closed
        assertEquals(1, collector.integrity.attributionFailures)
        // Guarantee must reflect failures
        assertEquals(CaptureGuarantee.INCOMPLETE, collector.integrity.guarantee)
    }

    @Test
    fun `default capture domain is sys_enter tracepoint and excludes seccomp denied attempts`() {
        val collector = AttributionCollector()

        // By default the capture domain must be SYS_ENTER_TRACEPOINT
        assertEquals(ObservationScope.SYS_ENTER_TRACEPOINT, collector.integrity.captureDomain)
        // This domain means seccomp-denied syscalls are NOT in the observed corpus
        // (documented invariant: absence of an event ≠ syscall was never attempted)
    }

    @Test
    fun `capture domain unknown can be declared explicitly`() {
        val collector = AttributionCollector(captureDomain = ObservationScope.UNKNOWN)

        assertEquals(ObservationScope.UNKNOWN, collector.integrity.captureDomain)
    }

    @Test
    fun `sys_enter tracepoint domain means only post-seccomp dispatched syscalls are observed`() {
        val collector = AttributionCollector(captureDomain = ObservationScope.SYS_ENTER_TRACEPOINT)

        // A successful observation in SYS_ENTER_TRACEPOINT scope confirms seccomp allowed the call
        val obs = pendingObservation(InvocationId(501))
        collector.accept(obs)

        assertEquals(ObservationScope.SYS_ENTER_TRACEPOINT, collector.integrity.captureDomain)
        // Domain has not changed and integrity is not incomplete from the observation alone
        assertEquals(0L, collector.integrity.observedLosses)
        assertEquals(0L, collector.integrity.attributionFailures)
    }

    @Test
    fun `dictionary drops recorded via recordDictionaryDrops surface as incomplete session`() {
        val collector = AttributionCollector()
        val invocationId = InvocationId(101)

        // Simulate encoder dropping an entry under pressure — caller notifies collector
        collector.recordDictionaryDrops(1)

        // Add a pending observation that will never resolve (its stack was dropped)
        collector.accept(pendingObservation(invocationId))

        // Finish: unresolved attribution surfaces as DICTIONARY_UNRESOLVED
        val finished = collector.finish()
        assertEquals(1, finished.size)
        assertEquals(ResolutionStatus.DICTIONARY_UNRESOLVED, finished.single().resolutionStatus)

        // Session is incomplete: both a loss (dropped dict) and an attribution failure
        assertEquals(1L, collector.integrity.observedLosses)
        assertEquals(CaptureGuarantee.INCOMPLETE, collector.integrity.guarantee)
    }

    @Test
    fun `unique edge deduplication retries after failed emit and suppresses after acknowledgement`() {
        val collector = AttributionCollector(emissionMode = TierEEmissionMode.UNIQUE_STACK_SYSCALL)
        val invocationId = InvocationId(200)
        val stack = StackDefinition(
            StackTraceId(1L),
            listOf(frame()),
            StackCaptureQuality.COMPLETE,
        )
        val invocation = InvocationDefinition(invocationId, null, stack.stackTraceId)

        // First observation — should be emitted (first unique edge)
        val obs1 = pendingObservation(invocationId)
        collector.accept(obs1)
        collector.accept(invocation)
        val r1 = collector.accept(stack)
        assertEquals(1, r1.size)
        assertEquals(ResolutionStatus.RESOLVED, r1.single().resolutionStatus)

        // Simulate a failed downstream emit: the same edge remains eligible for retry.
        val obs2 = pendingObservation(invocationId)
        val r2 = collector.accept(obs2)
        assertEquals(1, r2.size)

        // Once the first edge is acknowledged, later duplicates are suppressed.
        collector.acknowledgeEmission(r2.single())
        val obs3 = pendingObservation(invocationId)
        val r3 = collector.accept(obs3)
        assertEquals(0, r3.size)

        // Integrity: no failures from dedup suppression (it is by design, not a failure)
        assertEquals(0L, collector.integrity.attributionFailures)
    }

    private fun causalUncertainObservation(): SyscallAttribution =
        SyscallAttribution(
            task = TaskIdentity(1, 2, 3),
            taskSequence = 5,
            syscallNumber = 0,
            args = List(6) { 0 },
            invocationId = null,
            attributionStatus = AttributionStatus.CAUSAL_UNCERTAIN,
        )
}
