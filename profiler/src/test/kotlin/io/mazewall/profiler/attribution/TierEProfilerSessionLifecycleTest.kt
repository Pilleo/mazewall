package io.mazewall.profiler.internal.attribution
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import io.mazewall.profiler.tierE.daemon.UsdtProbeNote
import io.mazewall.profiler.tierE.ringbuf.SyscallInvocationEvent
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TierEProfilerSessionLifecycleTest {
    @Test
    fun `configured session epoch is retained for daemon artifact admission`() {
        val session = TierEProfilerSession(
            source = FakeTierEEventSource(),
            processEpoch = 1,
            configuredSessionEpoch = SessionEpoch(0x4d5a),
            definitionReader = { emptyDefinitions() },
        )

        assertEquals(SessionEpoch(0x4d5a), session.currentSessionEpoch())
    }

    @Test
    fun `finish is cached and polling after finish fails`() {
        val source = FakeTierEEventSource()
        var definitionReads = 0
        val session = TierEProfilerSession(
            source = source,
            processEpoch = 1,
            options = TierEOptions(),
            definitionReader = {
                definitionReads++
                emptyDefinitions()
            },
        )

        val first = session.finish(Path.of("ignored"))
        val second = session.finish(Path.of("different-and-never-read"))

        assertTrue(first === second)
        assertEquals(1, definitionReads)
        assertFailsWith<IllegalStateException> { session.poll() }
    }

    @Test
    fun `finish requires explicit producer closure for a complete lifecycle claim`() {
        val unproven = TierEProfilerSession(
            source = FakeTierEEventSource(),
            processEpoch = 1,
            definitionReader = { emptyDefinitions() },
        ).finish(Path.of("ignored"))
        val proven = TierEProfilerSession(
            source = FakeTierEEventSource(),
            processEpoch = 1,
            definitionReader = { emptyDefinitions() },
        ).finish(Path.of("ignored"), producerClosed = true)

        assertEquals(1, unproven.integrity.lifecycleInvalidations)
        assertEquals(0, proven.integrity.lifecycleInvalidations)
    }

    @Test
    fun `kernel quiescence makes final drain safe but does not prove producer closure`() {
        val source = FakeTierEEventSource(quiescentAfterDetach = true)
        val profile = TierEProfilerSession(
            source = source,
            processEpoch = 1,
            definitionReader = { emptyDefinitions() },
        ).finish(Path.of("ignored"))

        assertEquals(1, source.quiescenceCalls)
        assertEquals(1, profile.integrity.lifecycleInvalidations)
    }

    @Test
    fun `retained overflow drops new records and marks cached result incomplete`() {
        val source = FakeTierEEventSource(
            events = ArrayDeque(listOf(listOf(event(1uL)), listOf(event(2uL)))),
        )
        val session = TierEProfilerSession(
            source = source,
            processEpoch = 1,
            options = TierEOptions(maxRetainedRecords = 1),
            definitionReader = { emptyDefinitions() },
        )

        session.poll()
        session.poll()
        val profile = session.finish(Path.of("ignored"))

        assertEquals(1, profile.syscalls.size)
        assertEquals(1, profile.integrity.observedLosses)
        assertEquals(CaptureGuarantee.INCOMPLETE, profile.integrity.guarantee)
    }

    @Test
    fun `terminal definition record budget exhaustion is fail closed`() {
        val session = TierEProfilerSession(
            source = FakeTierEEventSource(),
            processEpoch = 1,
            definitionReader = { throw DefinitionRecordBudgetExceededException() },
        )

        val profile = session.finish(Path.of("ignored"), producerClosed = true)

        assertEquals(CaptureGuarantee.INCOMPLETE, profile.integrity.guarantee)
        assertSame(profile, session.finish(Path.of("never-read"), producerClosed = true))
    }

    @Test
    fun `terminal replay requests only pending invocation definitions`() {
        var requestedIds = emptySet<InvocationId>()
        val session = TierEProfilerSession(
            source = FakeTierEEventSource(events = ArrayDeque(listOf(listOf(event(1uL, invocationId = 7uL))))),
            processEpoch = 1,
            definitionReader = { emptyDefinitions() },
            selectiveDefinitionReader = { _, ids ->
                requestedIds = ids
                emptyDefinitions()
            },
        )

        session.poll()
        session.finish(Path.of("ignored"), producerClosed = true)

        assertEquals(setOf(InvocationId(7)), requestedIds)
    }

    @Test
    fun `close is terminal and idempotent`() {
        val source = FakeTierEEventSource()
        val session = TierEProfilerSession(
            source = source,
            processEpoch = 1,
            definitionReader = { emptyDefinitions() },
        )

        session.close()
        session.close()

        assertEquals(1, source.closeCalls)
        assertFailsWith<IllegalStateException> { session.poll() }
        assertFailsWith<IllegalStateException> { session.finish(Path.of("ignored")) }
    }

    @Test
    fun `marker detach invalidates attribution without closing session`() {
        val source = FakeTierEEventSource()
        val session = TierEProfilerSession(
            source = source,
            processEpoch = 1,
            definitionReader = { emptyDefinitions() },
        )

        session.detachMarker()

        assertEquals(1, source.detachMarkerCalls)
        assertEquals(0, source.closeCalls)
        session.close()
    }

    @Test
    fun `usdt attach uses the validated probe site rather than the plain marker entry`() {
        val source = FakeTierEEventSource()
        val session = TierEProfilerSession(
            source = source,
            processEpoch = 1,
            definitionReader = { emptyDefinitions() },
        )
        val note = UsdtProbeNote("mazewall", "stack_marker_v1", "8@%rdi 4@%esi", 0x4001004u, 0x1004, 0u)

        session.installAndAttach(42, 7, Path.of("agent.so"), markerOffset = 0x1000, usdtNote = note)
        session.detachMarker()

        assertEquals(listOf(0x1004L), source.usdtMarkerOffsets)
        assertTrue(source.markerOffsets.isEmpty())
        assertEquals(1, source.detachMarkerCalls)
        session.close()
    }

    @Test
    fun `usdt reentry uses the validated probe site after detach`() {
        val source = FakeTierEEventSource()
        val session = TierEProfilerSession(
            source = source,
            processEpoch = 1,
            definitionReader = { emptyDefinitions() },
        )
        val note = UsdtProbeNote("mazewall", "stack_marker_v1", "8@%rdi 4@%esi", 0x4001004u, 0x1004, 0u)

        session.installAndAttach(42, 7, Path.of("agent.so"), markerOffset = 0x1000, usdtNote = note)
        session.detachMarker()
        session.reattachMarker(42, Path.of("agent.so"), markerOffset = 0x1000, usdtNote = note)

        assertEquals(listOf(0x1004L, 0x1004L), source.usdtMarkerOffsets)
        assertTrue(source.markerOffsets.isEmpty())
        assertEquals(1, source.detachMarkerCalls)
        session.close()
    }

    @Test
    fun `live dictionary publishes definitions before session finish`() {
        val writes = mutableListOf<ByteArray>()
        val stack = StackDefinition(
            StackTraceId(7),
            listOf(ManagedFrame("app", "Foo", "work", "()V", 1, ManagedFrameKind.JAVA)),
            StackCaptureQuality.COMPLETE,
        )
        val session = TierEProfilerSession(
            source = FakeTierEEventSource(),
            processEpoch = 1,
            definitionReader = {
                AgentDefinitionFile.Contents(
                    stacks = listOf(stack),
                    invocations = emptyList(),
                    agentLosses = 0,
                )
            },
            dictionarySink = DictionaryBatchSink { batch ->
                writes += batch.bytes
                DictionarySinkResult.Committed
            },
        )

        session.enableLiveDictionary(Path.of("live-definitions"))
        session.poll()

        assertEquals(1, writes.size)
        assertTrue(writes.single().isNotEmpty())
        session.close()
    }

    @Test
    fun `terminal reconciliation retains native admission and capture outcomes`() {
        val session = TierEProfilerSession(
            source = FakeTierEEventSource(),
            processEpoch = 1,
            definitionReader = {
                AgentDefinitionFile.Contents(
                    stacks = emptyList(),
                    invocations = listOf(
                        InvocationDefinition(InvocationId(1), null, StackTraceId(7)),
                        InvocationDefinition(InvocationId(2), null, null, CaptureFailure.STACK_WALK_FAILED),
                    ),
                    agentLosses = 1,
                )
            },
        )

        val profile = session.finish(Path.of("ignored"))

        assertEquals(2, profile.integrity.reconciliation.admittedInvocations)
        assertEquals(1, profile.integrity.reconciliation.exactInvocations)
        assertEquals(1, profile.integrity.reconciliation.failedInvocations)
        assertEquals(0, profile.integrity.reconciliation.kernelActiveObservations)
    }

    @Test
    fun `benchmark session measures ring drain and collector join separately`() {
        val timestamps = ArrayDeque(listOf(10L, 20L, 40L, 60L))
        val session = TierEProfilerSession(
            source = FakeTierEEventSource(events = ArrayDeque(listOf(listOf(event(1uL))))),
            processEpoch = 1,
            options = TierEOptions(benchmarkPhases = true),
            definitionReader = { emptyDefinitions() },
            nanoTime = { timestamps.removeFirst() },
        )

        session.poll()

        assertEquals(10, session.benchmarkPhaseMetrics().ringDrainNanos)
        assertEquals(20, session.benchmarkPhaseMetrics().collectorJoinNanos)
        assertEquals(1, session.benchmarkPhaseMetrics().polls)
    }

    @Test
    fun `benchmark session reports allocation at whole drain and join boundaries`() {
        val timestamps = ArrayDeque(listOf(10L, 20L, 30L, 50L))
        val allocations = ArrayDeque(listOf(100L, 140L, 200L, 260L, 300L, 320L, 380L, 400L, 420L, 450L))
        val session = TierEProfilerSession(
            source = FakeTierEEventSource(events = ArrayDeque(listOf(listOf(event(1uL))))),
            processEpoch = 1,
            options = TierEOptions(benchmarkPhases = true, benchmarkAllocationAccounting = true),
            definitionReader = { emptyDefinitions() },
            nanoTime = { timestamps.removeFirst() },
            allocationCounter = TierEAllocationCounter { allocations.removeFirst() },
        )

        session.poll()

        val metrics = session.benchmarkPhaseMetrics()
        assertTrue(metrics.allocationMeasurementAvailable)
        assertEquals(40, metrics.ringDrainAllocatedBytes)
        assertEquals(200, metrics.collectorJoinAllocatedBytes)
        assertEquals(40, metrics.observationConversionAllocatedBytes)
        assertEquals(60, metrics.collectorResolutionAllocatedBytes)
        assertEquals(30, metrics.batchFreezeAllocatedBytes)
    }

    @Test
    fun `benchmark session counts ready records from owned decoded events without source probes`() {
        val source = FakeTierEEventSource(
            events = ArrayDeque(listOf(listOf(event(1uL), event(2uL)), listOf(event(3uL)))),
            drainMetrics = TierERingDrainPhaseMetrics(
                scanNanos = 3,
                decodeNanos = 5,
                appendNanos = 7,
                consumerPublishNanos = 11,
                scannedRecords = 13,
                readyRecords = 17,
            ),
        )
        val session = TierEProfilerSession(
            source = source,
            processEpoch = 1,
            options = TierEOptions(benchmarkPhases = true),
            definitionReader = { emptyDefinitions() },
            nanoTime = { 0L },
        )

        session.poll()
        session.poll()

        val metrics = session.benchmarkPhaseMetrics()
        assertEquals(6, metrics.ringScanNanos)
        assertEquals(10, metrics.ringDecodeNanos)
        assertEquals(14, metrics.ringAppendNanos)
        assertEquals(22, metrics.ringConsumerPublishNanos)
        assertEquals(26, metrics.ringScannedRecords)
        assertEquals(3, metrics.ringReadyRecords)
        assertEquals(2, metrics.ringBatches)
        assertEquals(2, metrics.ringMaxBatchRecords)
    }

    @Test
    fun `consecutive task identity control reuses only the same task`() {
        val session = TierEProfilerSession(
            source = FakeTierEEventSource(events = ArrayDeque(listOf(listOf(event(1uL, tid = 2u), event(2uL, tid = 2u), event(3uL, tid = 3u))))),
            processEpoch = 1,
            options = TierEOptions(benchmarkPhases = true, taskIdentityMode = TierETaskIdentityMode.REUSE_CONSECUTIVE),
            definitionReader = { emptyDefinitions() },
        )

        val resolved = session.poll()

        assertSame(resolved[0].observation.task, resolved[1].observation.task)
        assertNotSame(resolved[1].observation.task, resolved[2].observation.task)
        assertEquals(2, resolved[0].observation.task.tid)
        assertEquals(3, resolved[2].observation.task.tid)
    }

    @Test
    fun `poll consumes the decoded event list`() {
        val source = FakeTierEEventSource(events = ArrayDeque(listOf(listOf(event(1uL)))))
        val session = TierEProfilerSession(
            source = source,
            processEpoch = 1,
            definitionReader = { emptyDefinitions() },
        )

        session.poll()

        assertEquals(1, source.drainEventsCalls)
    }

    @Test
    fun `collector loop backs off only after an actually empty ring drain and resets after activity`() {
        val parks = mutableListOf<Long>()
        val source = FakeTierEEventSource(
            events = ArrayDeque(listOf(emptyList(), listOf(event(1uL)), emptyList())),
        )
        val loop = TierECollectorLoop(
            session = TierEProfilerSession(source, processEpoch = 1, definitionReader = { emptyDefinitions() }),
            policy = TierECollectorLoopPolicy.boundedAdaptive(initialIdleNanos = 10, maxIdleNanos = 40),
            parker = parks::add,
        )

        repeat(3) { loop.pollOnce() }

        assertEquals(listOf(10L, 10L), parks)
        assertEquals(3, loop.metrics().polls)
        assertEquals(1, loop.metrics().activePolls)
        assertEquals(2, loop.metrics().idlePolls)
        loop.close()
    }

    @Test
    fun `collector loop does not infer producer closure from an empty final drain`() {
        val source = FakeTierEEventSource()
        val loop = TierECollectorLoop(
            session = TierEProfilerSession(source, processEpoch = 1, definitionReader = { emptyDefinitions() }),
        )

        loop.pollOnce()
        val profile = loop.finish(Path.of("ignored"), producerClosed = false)

        assertEquals(1, profile.integrity.lifecycleInvalidations)
        assertFailsWith<IllegalStateException> { loop.pollOnce() }
        loop.close()
        assertEquals(1, source.closeCalls)
    }

    @Test
    fun `collector loop caps adaptive idle delay`() {
        val parks = mutableListOf<Long>()
        val loop = TierECollectorLoop(
            session = TierEProfilerSession(FakeTierEEventSource(), processEpoch = 1, definitionReader = { emptyDefinitions() }),
            policy = TierECollectorLoopPolicy.boundedAdaptive(initialIdleNanos = 10, maxIdleNanos = 40),
            parker = parks::add,
        )

        repeat(4) { loop.pollOnce() }

        assertEquals(listOf(10L, 20L, 40L, 40L), parks)
        assertEquals(4, loop.metrics().maxConsecutiveIdlePolls)
        loop.close()
    }

    @Test
    fun `collector loop records only nonnegative calibrated receipt ages`() {
        val source = FakeTierEEventSource(
            events = ArrayDeque(listOf(listOf(event(10uL), event(20uL), event(120uL)))),
        )
        val loop = TierECollectorLoop(
            session = TierEProfilerSession(source, processEpoch = 1, definitionReader = { emptyDefinitions() }),
            receiptClock = TierECollectorClock { 100L },
        )

        loop.pollOnce()

        val receiptAge = loop.metrics().receiptAge
        assertEquals(2, receiptAge.samples)
        assertEquals(80, receiptAge.minNanos)
        assertEquals(85, receiptAge.meanNanos)
        assertEquals(90, receiptAge.maxNanos)
        assertTrue(receiptAge.p99UpperBoundNanos >= receiptAge.maxNanos)
        loop.close()
    }

    private fun emptyDefinitions() = AgentDefinitionFile.Contents(emptyList(), emptyList(), 0)

    private fun event(
        sequence: ULong,
        tid: UInt = 2u,
        invocationId: ULong = 0u,
    ) = SyscallInvocationEvent(
        ktimeNs = sequence,
        tgid = 1u,
        tid = tid,
        syscallNr = 257,
        flags = 0,
        sequence = sequence,
        invocationId = invocationId,
        args = List(6) { 0u },
    )
}

private class FakeTierEEventSource(
    private val events: ArrayDeque<List<SyscallInvocationEvent>> = ArrayDeque(),
    private val quiescentAfterDetach: Boolean = false,
    private val drainMetrics: TierERingDrainPhaseMetrics = TierERingDrainPhaseMetrics(),
) : TierEEventSource {
    var closeCalls = 0
    var detachMarkerCalls = 0
    var drainEventsCalls = 0
    var quiescenceCalls = 0
    val markerOffsets = mutableListOf<Long>()
    val usdtMarkerOffsets = mutableListOf<Long>()

    override fun install(
        targetPid: Int,
        sessionTag: Int,
        options: TierEOptions,
    ) = Unit

    override fun attachMarker(
        targetPid: Int,
        agentLibrary: Path,
        markerOffset: Long,
    ) {
        markerOffsets += markerOffset
    }

    override fun attachUsdtMarker(
        targetPid: Int,
        agentLibrary: Path,
        note: UsdtProbeNote,
    ) {
        usdtMarkerOffsets += note.fileOffset
    }

    override fun detachMarker() {
        detachMarkerCalls++
    }

    override fun awaitSysEnterQuiescence(timeoutNanos: Long): Boolean {
        quiescenceCalls++
        return quiescentAfterDetach
    }

    override fun drainEvents(): List<SyscallInvocationEvent> {
        drainEventsCalls++
        return events.removeFirstOrNull().orEmpty()
    }

    override fun drainEventsMeasured(): TierERingEventDrain = TierERingEventDrain(drainEvents(), drainMetrics)

    override fun readLossCount(): Long = 0

    override fun readMarkerFailureCount(): Long = 0

    override fun readMarkerTransitionCount(): Long = 0

    override fun readLastMarkerPidTgid(): Long = 0

    override fun close() {
        closeCalls++
    }
}
