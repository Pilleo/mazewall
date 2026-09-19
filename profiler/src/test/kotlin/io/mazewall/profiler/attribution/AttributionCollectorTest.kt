package io.mazewall.profiler.internal.attribution
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
    fun `ready join transfer preserves order and is immutable`() {
        val joiner = InvocationDictionaryJoiner(maxPendingRecords = 3)
        val invocationId = InvocationId(101)
        val stackTraceId = StackTraceId(7)
        val first = pendingObservation(invocationId)
        val second = first.copy(taskSequence = 5)

        assertEquals(true, joiner.enqueue(first))
        assertEquals(true, joiner.enqueue(second))
        joiner.registerInvocation(InvocationDefinition(invocationId, parentInvocationId = null, stackTraceId = stackTraceId))
        joiner.registerStack(StackDefinition(stackTraceId, listOf(frame()), StackCaptureQuality.COMPLETE))

        val ready = requireNotNull(joiner.takeReady(invocationId))
        assertEquals(listOf(first, second), ready.observations)
        assertFailsWith<UnsupportedOperationException> {
            (ready.observations as MutableList).clear()
        }
        assertEquals(null, joiner.takeReady(invocationId))
    }

    @Test
    fun `ready join preserves a single pending observation without requiring a mutable bucket`() {
        val joiner = InvocationDictionaryJoiner(maxPendingRecords = 1)
        val invocationId = InvocationId(102)
        val stackTraceId = StackTraceId(8)
        val observation = pendingObservation(invocationId)

        assertEquals(true, joiner.enqueue(observation))
        joiner.registerInvocation(InvocationDefinition(invocationId, parentInvocationId = null, stackTraceId = stackTraceId))
        joiner.registerStack(StackDefinition(stackTraceId, listOf(frame()), StackCaptureQuality.COMPLETE))

        val ready = requireNotNull(joiner.takeReady(invocationId))
        assertEquals(listOf(observation), ready.observations)
        assertFailsWith<UnsupportedOperationException> {
            (ready.observations as MutableList).clear()
        }
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
    fun `out of domain syscall attempt is recorded as a boundary limitation`() {
        val collector = AttributionCollector()

        collector.recordOutOfDomainAttempt()

        assertEquals(1L, collector.integrity.outOfDomainAttempts)
        assertEquals(CaptureGuarantee.INCOMPLETE, collector.integrity.guarantee)
        assertEquals(false, collector.integrity.coverageComplete)
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

    @Test
    fun `unique edge audit distinguishes candidate acknowledgement and suppression`() {
        val decisions = mutableListOf<UniqueEdgeDecision>()
        val collector = AttributionCollector(
            emissionMode = TierEEmissionMode.UNIQUE_STACK_SYSCALL,
            uniqueEdgeDecisionObserver = decisions::add,
        )
        val invocationId = InvocationId(201)
        val stack = StackDefinition(StackTraceId(2L), listOf(frame()), StackCaptureQuality.COMPLETE)
        collector.accept(stack)
        collector.accept(InvocationDefinition(invocationId, null, stack.stackTraceId))

        val candidate = collector.accept(pendingObservation(invocationId)).single()
        collector.acknowledgeEmission(candidate)
        assertEquals(emptyList<ResolvedSyscall>(), collector.accept(pendingObservation(invocationId)))

        assertEquals(
            listOf(
                UniqueEdgeDecision.Kind.CANDIDATE,
                UniqueEdgeDecision.Kind.ACKNOWLEDGED,
                UniqueEdgeDecision.Kind.SUPPRESSED,
            ),
            decisions.map(UniqueEdgeDecision::kind),
        )
    }

    @Test
    fun `unique edge observer does not change resolved evidence or integrity`() {
        val observed = mutableListOf<UniqueEdgeDecision>()
        val observedCollector = AttributionCollector(
            emissionMode = TierEEmissionMode.UNIQUE_STACK_SYSCALL,
            uniqueEdgeDecisionObserver = observed::add,
        )
        val productionCollector = AttributionCollector(emissionMode = TierEEmissionMode.UNIQUE_STACK_SYSCALL)
        val invocationId = InvocationId(203)
        val stack = StackDefinition(StackTraceId(21L), listOf(frame()), StackCaptureQuality.COMPLETE)
        listOf(observedCollector, productionCollector).forEach { collector ->
            collector.accept(stack)
            collector.accept(InvocationDefinition(invocationId, null, stack.stackTraceId))
        }

        val observedCandidate = observedCollector.accept(pendingObservation(invocationId)).single()
        val productionCandidate = productionCollector.accept(pendingObservation(invocationId)).single()
        assertEquals(productionCandidate, observedCandidate)
        observedCollector.acknowledgeEmission(observedCandidate)
        productionCollector.acknowledgeEmission(productionCandidate)

        assertEquals(emptyList<ResolvedSyscall>(), observedCollector.accept(pendingObservation(invocationId)))
        assertEquals(emptyList<ResolvedSyscall>(), productionCollector.accept(pendingObservation(invocationId)))
        assertEquals(productionCollector.integrity, observedCollector.integrity)
        assertEquals(
            listOf(UniqueEdgeDecision.Kind.CANDIDATE, UniqueEdgeDecision.Kind.ACKNOWLEDGED, UniqueEdgeDecision.Kind.SUPPRESSED),
            observed.map(UniqueEdgeDecision::kind),
        )
    }

    @Test
    fun `unique edge allocation accounting separates observer diagnostics from ownership`() {
        val allocated = java.util.concurrent.atomic
            .AtomicLong()
        val collector = AttributionCollector(
            emissionMode = TierEEmissionMode.UNIQUE_STACK_SYSCALL,
            uniqueEdgeDecisionObserver = { allocated.addAndGet(100) },
            allocationCounter = TierEAllocationCounter { allocated.get() },
        )
        val invocationId = InvocationId(202)
        val stack = StackDefinition(StackTraceId(20L), listOf(frame()), StackCaptureQuality.COMPLETE)
        collector.accept(stack)
        collector.accept(InvocationDefinition(invocationId, null, stack.stackTraceId))

        val candidate = collector.accept(pendingObservation(invocationId)).single()
        collector.acknowledgeEmission(candidate)
        collector.accept(pendingObservation(invocationId))

        val allocation = collector.uniqueEdgeAllocationBreakdown()
        assertEquals(100L, allocation.candidateDiagnosticBytes)
        assertEquals(100L, allocation.suppressionDiagnosticBytes)
        assertEquals(0L, allocation.candidateOwnershipBytes)
        assertEquals(0L, allocation.gateCheckBytes)
    }

    @Test
    fun `emission acknowledgement requires the exact outstanding token`() {
        val collector = AttributionCollector(emissionMode = TierEEmissionMode.UNIQUE_STACK_SYSCALL)
        val invocationId = InvocationId(300)
        val stack = StackDefinition(StackTraceId(3L), listOf(frame()), StackCaptureQuality.COMPLETE)
        collector.accept(stack)
        collector.accept(InvocationDefinition(invocationId, null, stack.stackTraceId))
        val candidate = collector.accept(pendingObservation(invocationId)).single()
        val token = requireNotNull(collector.emissionToken(candidate))

        collector.acknowledgeEmission(token)
        assertFailsWith<IllegalArgumentException> { collector.acknowledgeEmission(token) }
        assertFailsWith<IllegalArgumentException> {
            collector.acknowledgeEmission(candidate.copy())
        }
        assertFailsWith<IllegalArgumentException> {
            collector.acknowledgeEmission(
                EmissionToken(999L, UniqueEdgeGate.Edge(StackTraceId(99L), 99, 0)),
            )
        }
    }

    @Test
    fun `stale process incarnation is rejected before dictionary joining`() {
        val collector = AttributionCollector(expectedProcessIncarnation = ProcessIncarnationId(2L))
        val stale = pendingObservation(InvocationId(301)).copy(
            task = TaskIdentity(processEpoch = 1L, tid = 42, taskEpoch = 1L),
        )

        val result = collector.accept(stale).single()
        assertEquals(ResolutionStatus.STALE_LIFECYCLE_IDENTITY, result.resolutionStatus)
        assertEquals(null, result.observation.invocationId)
        assertEquals(1L, collector.integrity.lifecycleInvalidations)
        assertEquals(false, collector.integrity.verdictDimensions.lifecycleValid)
    }

    @Test
    fun `capture domain attempt missed is a limitation and never an exact event`() {
        val collector = AttributionCollector()
        collector.recordCaptureDomainEvent(CaptureDomainEvent(CaptureDomainClassification.OBSERVED_SYS_ENTER))
        collector.recordCaptureDomainEvent(CaptureDomainEvent(CaptureDomainClassification.ATTEMPT_MISSED))

        assertEquals(1L, collector.integrity.outOfDomainAttempts)
        assertEquals(CaptureGuarantee.INCOMPLETE, collector.integrity.guarantee)
        assertEquals(false, collector.integrity.verdictDimensions.captureDomainValid)
    }

    @Test
    fun `detached attribution stays uncertain until explicit reentry`() {
        val collector = AttributionCollector()
        val invocation = InvocationId(401)
        val stack = StackDefinition(StackTraceId(4), listOf(frame()), StackCaptureQuality.COMPLETE)
        val observation = pendingObservation(invocation)
        collector.accept(stack)
        collector.accept(InvocationDefinition(invocation, null, stack.stackTraceId))

        collector.invalidateAttribution()
        val detached = collector.accept(observation).single()
        assertEquals(ResolutionStatus.CAUSAL_UNCERTAIN, detached.resolutionStatus)
        assertEquals(null, detached.observation.invocationId)

        collector.validateAttributionReentry()
        assertEquals(ResolutionStatus.RESOLVED, collector.accept(observation).single().resolutionStatus)
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
