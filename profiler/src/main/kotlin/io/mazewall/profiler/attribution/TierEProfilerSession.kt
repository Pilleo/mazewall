package io.mazewall.profiler.attribution

import io.mazewall.profiler.tierE.engine.TierEbpfEngine
import io.mazewall.profiler.tierE.ringbuf.SyscallInvocationEvent
import java.nio.file.Path

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

    fun drainEvents(): List<SyscallInvocationEvent>

    fun readLossCount(): Long

    fun readMarkerFailureCount(): Long

    fun readMarkerTransitionCount(): Long

    fun readLastMarkerPidTgid(): Long
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

    override fun drainEvents(): List<SyscallInvocationEvent> = engine.drainEvents()

    override fun readLossCount(): Long = engine.readLossCount()

    override fun readMarkerFailureCount(): Long = engine.readMarkerFailureCount()

    override fun readMarkerTransitionCount(): Long = engine.readMarkerTransitionCount()

    override fun readLastMarkerPidTgid(): Long = engine.readLastMarkerPidTgid()

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
public class TierEProfilerSession internal constructor(
    private val source: TierEEventSource,
    private val processEpoch: Long,
    private val options: TierEOptions = TierEOptions(),
    private val definitionReader: (Path) -> AgentDefinitionFile.Contents = AgentDefinitionFile::read,
    private val dictionarySink: DictionaryBatchSink? = null,
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

    private val sessionEpoch = SessionEpoch(processEpoch)
    private val collector = AttributionCollector(
        options.emissionMode,
        options.maxPendingRecords,
        expectedProcessIncarnation = ProcessIncarnationId(sessionEpoch.value),
    )
    private val dictionaryEncoder = dictionarySink?.let { StackDictionaryStreamEncoder(options.maxPendingRecords) }
    private val dictionaryPublisher = dictionarySink?.let { sink ->
        DictionaryBatchPublisher(requireNotNull(dictionaryEncoder), sink) { batch ->
            batch.definitions.forEach { definition -> retain(collector.accept(definition)) }
        }
    }
    private val resolved = mutableListOf<ResolvedSyscall>()
    private var reportedKernelLosses = 0L
    private var reportedMarkerFailures = 0L
    private var state: TierESessionState = TierESessionState.Collecting
    private var liveDefinitionFile: Path? = null
    private val liveKnownStackIds = mutableSetOf<StackTraceId>()

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
    ) {
        requireCollecting()
        source.install(targetPid, sessionTag, options)
        source.attachMarker(targetPid, agentLibrary, markerOffset)
    }

    /** Non-blocking drain suitable for a dedicated collector thread. */
    @Synchronized
    public fun poll(): List<ResolvedSyscall> {
        requireCollecting()
        val batch = source.drainEvents().flatMap { collector.accept(it.toAttribution()) }
        retain(batch)
        refreshLossCounters()
        publishLiveDefinitions()
        return java.util.List.copyOf(batch)
    }

    /** Completes delayed ID resolution once, then returns the same immutable result. */
    @Synchronized
    public fun finish(definitionFile: Path): TierEProfile =
        when (val current = state) {
            TierESessionState.Collecting -> finishCollecting(definitionFile)
            is TierESessionState.Finished -> current.profile
            TierESessionState.Closed -> error("Tier E profiler session is closed")
        }

    @Synchronized
    public fun markerTransitionCount(): Long {
        check(state != TierESessionState.Closed) { "Tier E profiler session is closed" }
        return source.readMarkerTransitionCount()
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
        source.close()
        state = TierESessionState.Closed
    }

    private fun finishCollecting(definitionFile: Path): TierEProfile {
        poll()
        val definitions = definitionReader(definitionFile)
        if (definitions.agentLosses > 0) collector.recordLoss(definitions.agentLosses)
        collector.recordVirtualPinnedIntervals(definitions.virtualPinnedScopes, definitions.virtualPinnedNanos)
        if (dictionaryPublisher == null) {
            definitions.stacks.forEach { retain(collector.accept(it)) }
        } else {
            definitions.stacks
                .filter { liveKnownStackIds.add(it.stackTraceId) }
                .forEach { requireNotNull(dictionaryEncoder).enqueue(it) }
            while (requireNotNull(dictionaryEncoder).pendingCount > 0) {
                if (dictionaryPublisher.publishAvailable(options.maxPendingRecords) != DictionaryPublicationStatus.COMMITTED) break
            }
            dictionaryPublisher.terminate()
            collector.recordDictionaryTransport(dictionaryPublisher.transportEvidence)
        }
        definitions.invocations.forEach { retain(collector.accept(it)) }
        retain(collector.finish())
        val profile = TierEProfile(resolved, collector.integrity)
        state = TierESessionState.Finished(profile)
        return profile
    }

    private fun publishLiveDefinitions() {
        val definitionFile = liveDefinitionFile ?: return
        val publisher = dictionaryPublisher ?: return
        val encoder = requireNotNull(dictionaryEncoder)
        val definitions = definitionReader(definitionFile)
        definitions.stacks
            .filter { liveKnownStackIds.add(it.stackTraceId) }
            .forEach(encoder::enqueue)
        while (encoder.pendingCount > 0) {
            if (publisher.publishAvailable(options.maxPendingRecords) != DictionaryPublicationStatus.COMMITTED) break
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
    }

    private fun requireCollecting() {
        check(state == TierESessionState.Collecting) { "Tier E profiler session is not collecting: $state" }
    }

    private fun SyscallInvocationEvent.toAttribution(): SyscallAttribution {
        val invalidFlags = flags and SyscallInvocationEvent.FLAG_ARGUMENT_READ_FAILED
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
        return SyscallAttribution(
            task = TaskIdentity.from(
                process = ProcessIncarnationId(sessionEpoch.value),
                tid = tid.toInt(),
                lifetime = TaskLifetimeId(0),
            ),
            taskSequence = sequence.toLong().takeIf { it > 0 } ?: ktimeNs.toLong(),
            syscallNumber = syscallNr,
            args = args.map(ULong::toLong),
            invocationId = active.takeIf { status == AttributionStatus.PENDING_DICTIONARY },
            attributionStatus = status,
            captureFlags = flags,
        )
    }
}

public class TierEProfile(
    syscalls: List<ResolvedSyscall>,
    public val integrity: SessionIntegrity,
) {
    public val syscalls: List<ResolvedSyscall> = java.util.List.copyOf(syscalls)

    override fun equals(other: Any?): Boolean = other is TierEProfile && syscalls == other.syscalls && integrity == other.integrity

    override fun hashCode(): Int = 31 * syscalls.hashCode() + integrity.hashCode()

    override fun toString(): String = "TierEProfile(syscalls=$syscalls, integrity=$integrity)"
}
