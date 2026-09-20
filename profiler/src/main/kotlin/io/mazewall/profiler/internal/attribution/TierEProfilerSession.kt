@file:Suppress("UseCheckNotNull")

package io.mazewall.profiler.internal.attribution

import io.mazewall.profiler.tierE.TierERetainedStateBudget
import io.mazewall.profiler.tierE.daemon.UsdtProbeNote
import io.mazewall.profiler.tierE.engine.TierEbpfEngine
import io.mazewall.profiler.tierE.ringbuf.SyscallInvocationEvent
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

internal enum class TierEEventReadiness { READY, TIMEOUT }

internal interface TierEEventSource : AutoCloseable {
    fun install(
        targetPid: Int,
        sessionTag: Int,
        options: TierEOptions,
    )

    fun attachMarker(
        targetPid: Int,
        agentLibrary: Path,
        markerOffset: Long,
    )

    fun attachUsdtMarker(
        targetPid: Int,
        agentLibrary: Path,
        note: UsdtProbeNote,
    ) {
        attachMarker(targetPid, agentLibrary, note.fileOffset)
    }

    fun detachMarker() {}

    fun drainEvents(): List<SyscallInvocationEvent>

    fun drainEventsMeasured(): TierERingEventDrain = TierERingEventDrain(drainEvents())

    /** A readiness notification is only a scheduling hint and never proves producer closure. */
    fun awaitEvents(timeoutMillis: Int): TierEEventReadiness = TierEEventReadiness.TIMEOUT

    fun readLossCount(): Long

    fun readMarkerFailureCount(): Long

    fun readAmbientInactiveCount(): Long = 0L

    fun readContextMetrics(): Map<Long, Long> = emptyMap()

    fun readMarkerTransitionCount(): Long

    fun readLastMarkerPidTgid(): Long

    /** Waits for handlers admitted before marker detachment to leave the kernel program. */
    fun awaitSysEnterQuiescence(timeoutNanos: Long): Boolean = false
}

private class NativeTierEEventSource(
    private val engine: TierEbpfEngine,
) : TierEEventSource {
    override fun install(
        targetPid: Int,
        sessionTag: Int,
        options: TierEOptions,
    ) = engine.install(targetPid, sessionTag, options)

    override fun attachMarker(
        targetPid: Int,
        agentLibrary: Path,
        markerOffset: Long,
    ) = engine.attachMarker(targetPid, agentLibrary, markerOffset)

    override fun attachUsdtMarker(
        targetPid: Int,
        agentLibrary: Path,
        note: UsdtProbeNote,
    ) = engine.attachUsdtMarker(targetPid, agentLibrary, note)

    override fun detachMarker() = engine.detachMarker()

    override fun drainEvents(): List<SyscallInvocationEvent> = engine.drainEvents()

    override fun drainEventsMeasured(): TierERingEventDrain = engine.drainEventsMeasured()

    override fun awaitEvents(timeoutMillis: Int): TierEEventReadiness = engine.awaitEvents(timeoutMillis)

    override fun readLossCount(): Long = engine.readLossCount()

    override fun readMarkerFailureCount(): Long = engine.readMarkerFailureCount()

    override fun readAmbientInactiveCount(): Long = engine.readAmbientInactiveCount()

    override fun readContextMetrics(): Map<Long, Long> = engine.readContextMetrics()

    override fun readMarkerTransitionCount(): Long = engine.readMarkerTransitionCount()

    override fun readLastMarkerPidTgid(): Long = engine.readLastMarkerPidTgid()

    override fun awaitSysEnterQuiescence(timeoutNanos: Long): Boolean = engine.awaitSysEnterQuiescence(timeoutNanos)

    override fun close() = engine.close()
}

internal sealed interface TierESessionState {
    data object Collecting : TierESessionState

    data class Finished(
        val profile: TierEProfile,
    ) : TierESessionState

    data object Closed : TierESessionState
}

/**
 * Owns the supplied eBPF engine/event source and the collected immutable evidence.
 * [finish] freezes one cached result; [close] retires the source without reopening collection.
 * Callers must close this session even after finishing and must not separately close its source.
 */
internal class TierEProfilerSession internal constructor(
    private val source: TierEEventSource,
    private val processEpoch: Long,
    private val options: TierEOptions = TierEOptions(),
    private val definitionReader: (Path) -> AgentDefinitionFile.Contents = AgentDefinitionFile::read,
    private val dictionarySink: DictionaryBatchSink? = null,
    private val uniqueEdgeDecisionObserver: ((UniqueEdgeDecision) -> Unit)? = null,
    private val nanoTime: () -> Long = System::nanoTime,
    private val allocationCounter: TierEAllocationCounter = TierEAllocationCounter { TierEAllocationCounters.currentThreadAllocatedBytes() },
    private val selectiveDefinitionReader: (Path, Set<InvocationId>) -> AgentDefinitionFile.Contents = AgentDefinitionFile::readForInvocations,
) : AutoCloseable {
    public constructor(
        engine: TierEbpfEngine,
        processEpoch: Long,
        options: TierEOptions = TierEOptions(),
    ) : this(NativeTierEEventSource(engine), processEpoch, options)

    public constructor(
        engine: TierEbpfEngine,
        processEpoch: Long,
        options: TierEOptions,
        dictionarySink: DictionaryBatchSink,
    ) : this(NativeTierEEventSource(engine), processEpoch, options, AgentDefinitionFile::read, dictionarySink)

    internal constructor(
        engine: TierEbpfEngine,
        processEpoch: Long,
        options: TierEOptions,
        uniqueEdgeDecisionObserver: ((UniqueEdgeDecision) -> Unit)?,
    ) : this(
        NativeTierEEventSource(engine),
        processEpoch,
        options,
        AgentDefinitionFile::read,
        null,
        uniqueEdgeDecisionObserver,
    )

    private val sessionEpoch = SessionEpoch(NEXT_SESSION_EPOCH.incrementAndGet())
    private val processIncarnation = ProcessIncarnationId(processEpoch)
    private val retainedBudget = TierERetainedStateBudget(options.maxRetainedBytes)
    private val collector = AttributionCollector(
        options.emissionMode,
        options.maxPendingRecords,
        expectedProcessIncarnation = processIncarnation,
        maxRetainedBytes = options.maxRetainedBytes,
        readyTransferMode = options.readyTransferMode,
        knownJoinMode = options.knownJoinMode,
        uniqueEdgeDecisionObserver = uniqueEdgeDecisionObserver,
        allocationCounter = allocationCounter.takeIf { options.benchmarkAllocationAccounting },
    )
    private val dictionaryEncoder = dictionarySink?.let {
        StackDictionaryStreamEncoder(options.maxPendingRecords).also { encoder ->
            encoder.attachRetainedBudget(retainedBudget)
        }
    }
    private val dictionaryPublisher = dictionarySink?.let { sink ->
        DictionaryBatchPublisher(requireNotNull(dictionaryEncoder), sink) { batch ->
            batch.definitions.forEach { definition -> retain(collector.accept(definition)) }
        }
    }
    private val resolved = mutableListOf<ResolvedSyscall>()
    private var reportedKernelLosses = 0L
    private var reportedMarkerFailures = 0L
    private var reportedAmbientInactive = 0L
    private var state: TierESessionState = TierESessionState.Collecting
    private var sourceClosed = false
    private var lifecycle: TierESessionLifecycleState = TierESessionLifecycleState.RUNNING
    private var liveDefinitionFile: Path? = null
    private val liveKnownStackIds = mutableSetOf<StackTraceId>()
    private val liveDefinitionAdmission = SnapshotReplayAdmission()
    private var ringDrainNanos = 0L
    private var ringScanNanos = 0L
    private var ringPayloadSliceNanos = 0L
    private var ringDecodeNanos = 0L
    private var ringAppendNanos = 0L
    private var ringConsumerPublishNanos = 0L
    private var ringScannedRecords = 0L
    private var ringReadyRecords = 0L
    private var ringReaderInitializationNanos = 0L
    private var collectorJoinNanos = 0L
    private var collectorPolls = 0L
    private var allocationMeasurementAvailable = false
    private var ringDrainAllocatedBytes = 0L
    private var collectorJoinAllocatedBytes = 0L
    private var observationConversionAllocatedBytes = 0L
    private var collectorResolutionAllocatedBytes = 0L
    private var batchFreezeAllocatedBytes = 0L
    private var lastTaskIdentity: TaskIdentity? = null

    init {
        collector.attachRetainedBudget(retainedBudget)
    }

    /** Enables opt-in publication of definitions while the session is collecting. */
    @Synchronized
    public fun enableLiveDictionary(definitionFile: Path) {
        requireCollecting()
        check(dictionaryPublisher != null) { "live dictionary publication requires a dictionary sink" }
        liveDefinitionFile = definitionFile
    }

    @Synchronized
    public fun installAndAttach(
        targetPid: Int,
        sessionTag: Int,
        agentLibrary: Path,
        markerOffset: Long,
        usdtNote: UsdtProbeNote? = null,
    ) {
        requireCollecting()
        lifecycle = TierESessionLifecycleState.ATTACHING
        source.install(targetPid, sessionTag, options)
        if (usdtNote == null) {
            source.attachMarker(targetPid, agentLibrary, markerOffset)
        } else {
            source.attachUsdtMarker(targetPid, agentLibrary, usdtNote)
        }
        lifecycle = TierESessionLifecycleState.RUNNING
    }

    /** Non-blocking drain suitable for a dedicated collector thread. */
    @Synchronized
    public fun poll(): List<ResolvedSyscall> = pollOutcome().resolved

    @Synchronized
    internal fun awaitEvents(timeoutMillis: Int): TierEEventReadiness {
        requireCollecting()
        return source.awaitEvents(timeoutMillis)
    }

    /** Internal drain outcome so collector pacing follows actual ring activity, not join timing. */
    @Synchronized
    internal fun pollOutcome(receiptClock: TierECollectorClock? = null): TierECollectorPoll {
        requireCollecting()
        val events = if (options.benchmarkPhases) {
            val allocationBefore = allocationBeforePhase()
            val started = nanoTime()
            source
                .drainEventsMeasured()
                .also { drained ->
                ringDrainNanos += nanoTime() - started
                ringScanNanos += drained.metrics.scanNanos
                ringPayloadSliceNanos += drained.metrics.payloadSliceNanos
                ringDecodeNanos += drained.metrics.decodeNanos
                ringAppendNanos += drained.metrics.appendNanos
                ringConsumerPublishNanos += drained.metrics.consumerPublishNanos
                ringScannedRecords += drained.metrics.scannedRecords
                // The production reader deliberately has no per-record clocks. Every returned
                // event is an owned READY record, so this denominator is exact without adding
                // measurement work to the record loop.
                ringReadyRecords += drained.events.size
                ringReaderInitializationNanos += drained.metrics.readerInitializationNanos
            }.events
                .also {
                val allocated = allocationDelta(allocationBefore)
                if (allocated != TierEAllocationCounters.UNAVAILABLE) ringDrainAllocatedBytes += allocated
            }
        } else {
            source.drainEvents()
        }
        val receiptNanos = receiptClock?.nowNanos()
        val receiptAgesNanos = receiptNanos?.let { receipt ->
            events
                .mapNotNull { event ->
                event.ktimeNs
                    .takeIf { it <= Long.MAX_VALUE.toULong() }
                    ?.toLong()
                    ?.let { eventTime -> (receipt - eventTime).takeIf { it >= 0L } }
            }.toLongArray()
        } ?: LongArray(0)
        val batch = if (options.benchmarkPhases) {
            val allocationBefore = allocationBeforePhase()
            val started = nanoTime()
            resolveDrainedEvents(events)
                .also {
                collectorJoinNanos += nanoTime() - started
                collectorPolls++
            }.also {
                val allocated = allocationDelta(allocationBefore)
                if (allocated != TierEAllocationCounters.UNAVAILABLE) collectorJoinAllocatedBytes += allocated
            }
        } else {
            resolveDrainedEvents(events)
        }
        val retainedSnapshot = if (options.benchmarkPhases) {
            val allocationBefore = allocationBeforePhase()
            retain(batch)
            java.util.List.copyOf(batch).also {
                val allocated = allocationDelta(allocationBefore)
                if (allocated != TierEAllocationCounters.UNAVAILABLE) batchFreezeAllocatedBytes += allocated
            }
        } else {
            retain(batch)
            java.util.List.copyOf(batch)
        }
        refreshLossCounters()
        publishLiveDefinitions()
        return TierECollectorPoll(retainedSnapshot, events.size, receiptAgesNanos)
    }

    /** Preserves event order while avoiding the intermediate collections created by [Iterable.flatMap]. */
    private fun resolveDrainedEvents(events: List<SyscallInvocationEvent>): List<ResolvedSyscall> {
        if (events.isEmpty()) return emptyList()
        val resolved = ArrayList<ResolvedSyscall>(events.size)
        for (event in events) {
            val conversionAllocationBefore = allocationBeforePhase()
            val observation = event.toAttribution()
            val conversionAllocated = allocationDelta(conversionAllocationBefore)
            if (conversionAllocated != TierEAllocationCounters.UNAVAILABLE) observationConversionAllocatedBytes += conversionAllocated
            val resolutionAllocationBefore = allocationBeforePhase()
            if (options.collectorResultMode == TierECollectorResultMode.APPEND_TARGET) {
                collector.acceptInto(observation, resolved)
            } else {
                resolved.addAll(collector.accept(observation))
            }
            val resolutionAllocated = allocationDelta(resolutionAllocationBefore)
            if (resolutionAllocated != TierEAllocationCounters.UNAVAILABLE) collectorResolutionAllocatedBytes += resolutionAllocated
        }
        return resolved
    }

    @Synchronized
    internal fun benchmarkPhaseMetrics(): TierECollectorPhaseMetrics =
        TierECollectorPhaseMetrics(
        ringDrainNanos = ringDrainNanos,
        ringScanNanos = ringScanNanos,
        ringPayloadSliceNanos = ringPayloadSliceNanos,
        ringDecodeNanos = ringDecodeNanos,
        ringAppendNanos = ringAppendNanos,
        ringConsumerPublishNanos = ringConsumerPublishNanos,
        ringScannedRecords = ringScannedRecords,
        ringReadyRecords = ringReadyRecords,
        ringReaderInitializationNanos = ringReaderInitializationNanos,
        collectorJoinNanos = collectorJoinNanos,
        polls = collectorPolls,
        allocationMeasurementAvailable = allocationMeasurementAvailable,
        ringDrainAllocatedBytes = ringDrainAllocatedBytes,
        collectorJoinAllocatedBytes = collectorJoinAllocatedBytes,
        observationConversionAllocatedBytes = observationConversionAllocatedBytes,
        collectorResolutionAllocatedBytes = collectorResolutionAllocatedBytes,
        resolvedEvidenceAllocatedBytes = collector.resolvedEvidenceAllocatedBytes(),
        joinerEnqueueAllocatedBytes = collector.joinerAllocationBreakdown().enqueueBytes,
        joinerTakeReadyAllocatedBytes = collector.joinerAllocationBreakdown().takeReadyBytes,
        uniqueEdgeGateAllocatedBytes = collector.uniqueEdgeAllocationBreakdown().gateCheckBytes,
        uniqueEdgeSuppressionDiagnosticAllocatedBytes = collector.uniqueEdgeAllocationBreakdown().suppressionDiagnosticBytes,
        uniqueEdgeCandidateOwnershipAllocatedBytes = collector.uniqueEdgeAllocationBreakdown().candidateOwnershipBytes,
        uniqueEdgeCandidateDiagnosticAllocatedBytes = collector.uniqueEdgeAllocationBreakdown().candidateDiagnosticBytes,
        batchFreezeAllocatedBytes = batchFreezeAllocatedBytes,
    )

    private fun allocationBeforePhase(): Long = if (options.benchmarkAllocationAccounting) allocationCounter.currentThreadAllocatedBytes() else TierEAllocationCounters.UNAVAILABLE

    private fun allocationDelta(before: Long): Long {
        if (before == TierEAllocationCounters.UNAVAILABLE) return TierEAllocationCounters.UNAVAILABLE
        val after = allocationCounter.currentThreadAllocatedBytes()
        if (after == TierEAllocationCounters.UNAVAILABLE || after < before) return TierEAllocationCounters.UNAVAILABLE
        allocationMeasurementAvailable = true
        return after - before
    }

    /**
     * Completes delayed ID resolution once, then returns the same immutable result.
     *
     * [producerClosed] is an externally proven producer-quiescence fact, not an inference from
     * an empty ring drain. It defaults to false so a caller cannot accidentally publish a
     * complete terminal verdict while native scopes may still be producing observations.
     */
    @Synchronized
    public fun finish(
        definitionFile: Path,
        producerClosed: Boolean = false,
    ): TierEProfile =
        when (val current = state) {
            TierESessionState.Collecting -> finishCollecting(definitionFile, producerClosed)
            is TierESessionState.Finished -> current.profile
            TierESessionState.Closed -> error("Tier E profiler session is closed")
        }

    @Synchronized
    public fun markerTransitionCount(): Long {
        check(state != TierESessionState.Closed) { "Tier E profiler session is closed" }
        return source.readMarkerTransitionCount()
    }

    /** Declared native capture scope, separate from observed attribution and evidence completeness. */
    internal fun captureProfile(): TierECaptureProfile = options.captureProfile

    /** Typed identity used to reject a definition artifact from another live session. */
    @Synchronized
    internal fun currentSessionEpoch(): SessionEpoch = sessionEpoch

    /** Collector ownership may start only while this session can still accept owned ring records. */
    @Synchronized
    internal fun isCollectorStartable(): Boolean = state == TierESessionState.Collecting && lifecycle == TierESessionLifecycleState.RUNNING

    /** Detaches marker admission while keeping the syscall observer alive. */
    @Synchronized
    public fun detachMarker() {
        requireCollecting()
        check(lifecycle == TierESessionLifecycleState.RUNNING) { "attribution lifecycle is not running" }
        source.detachMarker()
        collector.invalidateAttribution()
    }

    /** Returns the monotonic kernel-active observation total at a lifecycle boundary. */
    public fun kernelActiveObservationCount(): Long = source.readContextMetrics().values.fold(0L, Math::addExact)

    /** Reattaches a marker only as an explicit validated attribution re-entry. */
    @Synchronized
    public fun reattachMarker(
        targetPid: Int,
        agentLibrary: Path,
        markerOffset: Long,
        usdtNote: UsdtProbeNote? = null,
    ) {
        requireCollecting()
        check(lifecycle == TierESessionLifecycleState.RUNNING) { "attribution lifecycle is not running" }
        if (usdtNote == null) {
            source.attachMarker(targetPid, agentLibrary, markerOffset)
        } else {
            source.attachUsdtMarker(targetPid, agentLibrary, usdtNote)
        }
        collector.validateAttributionReentry()
    }

    @Synchronized
    public fun lastMarkerPidTgid(): Long {
        check(state != TierESessionState.Closed) { "Tier E profiler session is closed" }
        return source.readLastMarkerPidTgid()
    }

    /** Records an upstream capture-domain limitation without fabricating a syscall event. */
    @Synchronized
    public fun recordCaptureDomainEvent(event: CaptureDomainEvent) {
        requireCollecting()
        collector.recordCaptureDomainEvent(event)
    }

    @Synchronized
    override fun close() {
        if (state == TierESessionState.Closed) return
        lifecycle = TierESessionLifecycleState.CLOSING
        try {
            closeSource()
            state = TierESessionState.Closed
        } finally {
            lifecycle = TierESessionLifecycleState.DEAD
        }
    }

    private fun finishCollecting(
        definitionFile: Path,
        producerClosed: Boolean,
    ): TierEProfile {
        lifecycle = TierESessionLifecycleState.CLOSING
        // Stop marker admission before the final drain. A caller that cannot prove producer
        // closure must leave a durable lifecycle invalidation rather than treating an empty
        // ring as a producer barrier.
        source.detachMarker()
        val quiescent = producerClosed || source.awaitSysEnterQuiescence(TERMINAL_QUIESCENCE_TIMEOUT_NANOS)
        // The counter proves that the detached BPF program cannot append an
        // already-admitted ring record after this drain. It does not prove the
        // native agent has stopped producing future definitions, so it cannot
        // replace the caller's independent producer-closure proof.
        if (!producerClosed || !quiescent) collector.invalidateAttribution()
        try {
            poll()
        } catch (_: Exception) {
            // A terminal drain failure cannot become a complete profile. Preserve every result
            // already retained, but account for the unread tail before closing the source.
            collector.recordLoss()
            collector.invalidateAttribution()
        }
        val reconciliation = TierEReconciliation(
            admittedInvocations = 0L,
            exactInvocations = 0L,
            failedInvocations = 0L,
            kernelActiveObservations = source.readContextMetrics().values.fold(0L, Math::addExact),
            markerTransitions = source.readMarkerTransitionCount(),
        )
        closeSource()
        val definitions = try {
            readDefinitions(definitionFile, collector.pendingInvocationIds())
        } catch (_: DefinitionRecordBudgetExceededException) {
            collector.recordDictionaryConflict()
            AgentDefinitionFile.Contents(emptyList(), emptyList(), 0)
        }
        if (definitions.agentLosses > 0) collector.recordLoss(definitions.agentLosses)
        collector.recordVirtualPinnedIntervals(definitions.virtualPinnedScopes, definitions.virtualPinnedNanos)
        if (dictionaryPublisher == null) {
            definitions.stacks.forEach { retain(collector.accept(it)) }
        } else {
            definitions.stacks
                .filter(::admitLiveStack)
                .forEach { requireNotNull(dictionaryEncoder).enqueue(it) }
            while (requireNotNull(dictionaryEncoder).pendingCount > 0) {
                if (dictionaryPublisher.publishAvailable(options.maxPendingRecords) != DictionaryPublicationStatus.COMMITTED) break
            }
            dictionaryPublisher.terminate()
            collector.recordDictionaryTransport(dictionaryPublisher.transportEvidence)
        }
        definitions.invocations.forEach { retain(collector.accept(it)) }
        collector.recordReconciliation(
            reconciliation.copy(
                admittedInvocations = definitions.invocations.size.toLong(),
                exactInvocations = definitions.invocations.count { it.captureFailure == null }.toLong(),
                failedInvocations = definitions.invocations.count { it.captureFailure != null }.toLong(),
            ),
        )
        retain(collector.finish())
        val profile = TierEProfile(
            resolved,
            collector.integrity.copy(publicationMetrics = dictionaryPublisher?.metrics ?: SnapshotPublicationMetrics()),
        )
        state = TierESessionState.Finished(profile)
        lifecycle = TierESessionLifecycleState.DEAD
        return profile
    }

    private fun closeSource() {
        if (sourceClosed) return
        source.close()
        sourceClosed = true
    }

    private fun readDefinitions(
        definitionFile: Path,
        pendingInvocationIds: Set<InvocationId>? = null,
    ): AgentDefinitionFile.Contents =
        (
            if (pendingInvocationIds.isNullOrEmpty()) {
            definitionReader(definitionFile)
        } else {
            selectiveDefinitionReader(definitionFile, pendingInvocationIds)
        }
        ).also { definitions ->
            definitions.publication?.sessionEpoch?.let { publicationSession ->
                check(publicationSession == sessionEpoch) {
                    "definition publication belongs to session $publicationSession, expected $sessionEpoch"
                }
            }
        }

    private fun publishLiveDefinitions() {
        val definitionFile = liveDefinitionFile ?: return
        val publisher = dictionaryPublisher ?: return
        val encoder = requireNotNull(dictionaryEncoder)
        val definitions = readDefinitions(definitionFile)
        definitions.stacks
            .filter(::admitLiveStack)
            .forEach(encoder::enqueue)
        while (encoder.pendingCount > 0) {
            if (publisher.publishAvailable(options.maxPendingRecords) != DictionaryPublicationStatus.COMMITTED) break
        }
    }

    private fun admitLiveStack(definition: StackDefinition): Boolean =
        when (liveDefinitionAdmission.admit(definition)) {
            DefinitionAdmission.NEW -> liveKnownStackIds.add(definition.stackTraceId)
            DefinitionAdmission.DUPLICATE_IDENTICAL -> false
            DefinitionAdmission.CONFLICTING -> {
                collector.recordDictionaryConflict()
                false
            }
        }

    private fun retain(batch: List<ResolvedSyscall>) {
        val accepted = (options.maxRetainedRecords - resolved.size).coerceAtLeast(0).coerceAtMost(batch.size)
        if (accepted > 0) {
            val retained = batch.subList(0, accepted)
            resolved.addAll(retained)
            retained.forEach(collector::acknowledgeEmission)
        }
        val discarded = batch.size - accepted
        if (discarded > 0) collector.recordLoss(discarded.toLong())
    }

    private fun refreshLossCounters() {
        val losses = source.readLossCount()
        check(losses >= reportedKernelLosses) { "kernel loss counter moved backwards" }
        if (losses != reportedKernelLosses) {
            collector.recordLoss(losses - reportedKernelLosses)
            reportedKernelLosses = losses
        }
        val markerFailures = source.readMarkerFailureCount()
        check(markerFailures >= reportedMarkerFailures) { "marker failure counter moved backwards" }
        if (markerFailures != reportedMarkerFailures) {
            collector.recordLoss(markerFailures - reportedMarkerFailures)
            reportedMarkerFailures = markerFailures
        }
        val ambientInactive = source.readAmbientInactiveCount()
        check(ambientInactive >= reportedAmbientInactive) { "ambient inactive counter moved backwards" }
        if (ambientInactive != reportedAmbientInactive) {
            collector.recordAmbientInactive(ambientInactive - reportedAmbientInactive)
            reportedAmbientInactive = ambientInactive
        }
    }

    private fun requireCollecting() {
        check(state == TierESessionState.Collecting) { "Tier E profiler session is not collecting: $state" }
        check(lifecycle != TierESessionLifecycleState.DEAD) { "Tier E profiler session is dead" }
    }

    private fun SyscallInvocationEvent.toAttribution(): SyscallAttribution {
        val invalidFlags = flags and SyscallInvocationEvent.FLAG_ARGUMENT_READ_FAILED
        val task = taskIdentityFor(tid.toInt())
        val active = invocationId
            .takeIf { it != 0uL }
            ?.toLong()
            ?.takeIf { it > 0 }
            ?.let(::InvocationId)
        val status = when {
            invalidFlags != 0 -> AttributionStatus.KERNEL_CAPTURE_FAILED
            active == null -> AttributionStatus.NO_ACTIVE_INVOCATION
            else -> AttributionStatus.PENDING_DICTIONARY
        }
        if (!options.ownedArgumentStorage) {
            return SyscallAttribution(
                task = task,
                taskSequence = sequence.toLong().takeIf { it > 0 } ?: ktimeNs.toLong(),
                syscallNumber = syscallNr,
                args = args.map(ULong::toLong),
                invocationId = active.takeIf { status == AttributionStatus.PENDING_DICTIONARY },
                attributionStatus = status,
                captureFlags = flags,
            )
        }
        return SyscallAttribution.fromOwnedArguments(
            task = task,
            taskSequence = sequence.toLong().takeIf { it > 0 } ?: ktimeNs.toLong(),
            syscallNumber = syscallNr,
            ownedArgs = takeOwnedArguments(),
            invocationId = active.takeIf { status == AttributionStatus.PENDING_DICTIONARY },
            attributionStatus = status,
            captureFlags = flags,
        )
    }

    private companion object {
        private val NEXT_SESSION_EPOCH = AtomicLong()
        private const val TERMINAL_QUIESCENCE_TIMEOUT_NANOS = 1_000_000_000L
    }

    private fun taskIdentityFor(tid: Int): TaskIdentity {
        if (options.taskIdentityMode == TierETaskIdentityMode.REUSE_CONSECUTIVE) {
            lastTaskIdentity?.takeIf { it.tid == tid }?.let { return it }
        }
        return TaskIdentity.from(processIncarnation, tid, TaskLifetimeId(0)).also { identity ->
            if (options.taskIdentityMode == TierETaskIdentityMode.REUSE_CONSECUTIVE) lastTaskIdentity = identity
        }
    }
}

internal class TierEProfile(
    syscalls: List<ResolvedSyscall>,
    public val integrity: SessionIntegrity,
) {
    public val syscalls: List<ResolvedSyscall> = java.util.List.copyOf(syscalls)

    override fun equals(other: Any?): Boolean = other is TierEProfile && syscalls == other.syscalls && integrity == other.integrity

    override fun hashCode(): Int = 31 * syscalls.hashCode() + integrity.hashCode()

    override fun toString(): String = "TierEProfile(syscalls=$syscalls, integrity=$integrity)"
}

/** Benchmark-only userspace collector phase totals; zero outside explicitly instrumented runs. */
internal data class TierECollectorPhaseMetrics(
    val ringDrainNanos: Long = 0,
    val ringScanNanos: Long = 0,
    val ringPayloadSliceNanos: Long = 0,
    val ringDecodeNanos: Long = 0,
    val ringAppendNanos: Long = 0,
    val ringConsumerPublishNanos: Long = 0,
    val ringScannedRecords: Long = 0,
    val ringReadyRecords: Long = 0,
    val ringReaderInitializationNanos: Long = 0,
    val collectorJoinNanos: Long = 0,
    val polls: Long = 0,
    val allocationMeasurementAvailable: Boolean = false,
    val ringDrainAllocatedBytes: Long = 0,
    val collectorJoinAllocatedBytes: Long = 0,
    val observationConversionAllocatedBytes: Long = 0,
    val collectorResolutionAllocatedBytes: Long = 0,
    val resolvedEvidenceAllocatedBytes: Long = 0,
    val joinerEnqueueAllocatedBytes: Long = 0,
    val joinerTakeReadyAllocatedBytes: Long = 0,
    val uniqueEdgeGateAllocatedBytes: Long = 0,
    val uniqueEdgeSuppressionDiagnosticAllocatedBytes: Long = 0,
    val uniqueEdgeCandidateOwnershipAllocatedBytes: Long = 0,
    val uniqueEdgeCandidateDiagnosticAllocatedBytes: Long = 0,
    val batchFreezeAllocatedBytes: Long = 0,
)

/** Owned events plus benchmark-only ring-drain sub-phase totals. */
internal data class TierERingEventDrain(
    val events: List<SyscallInvocationEvent>,
    val metrics: TierERingDrainPhaseMetrics = TierERingDrainPhaseMetrics(),
)

internal data class TierECollectorPoll(
    val resolved: List<ResolvedSyscall>,
    val drainedEventCount: Int,
    val receiptAgesNanos: LongArray = LongArray(0),
)

internal data class TierERingDrainPhaseMetrics(
    val scanNanos: Long = 0,
    val payloadSliceNanos: Long = 0,
    val decodeNanos: Long = 0,
    val appendNanos: Long = 0,
    val consumerPublishNanos: Long = 0,
    val scannedRecords: Long = 0,
    val readyRecords: Long = 0,
    val readerInitializationNanos: Long = 0,
)
