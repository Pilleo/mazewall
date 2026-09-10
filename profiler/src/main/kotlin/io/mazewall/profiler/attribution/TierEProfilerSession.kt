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
) : AutoCloseable {
    public constructor(
        engine: TierEbpfEngine,
        processEpoch: Long,
        options: TierEOptions = TierEOptions(),
    ) : this(NativeTierEEventSource(engine), processEpoch, options)

    private val collector = AttributionCollector(options.emissionMode, options.maxPendingRecords)
    private val resolved = mutableListOf<ResolvedSyscall>()
    private var reportedKernelLosses = 0L
    private var reportedMarkerFailures = 0L
    private var state: TierESessionState = TierESessionState.Collecting

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
        definitions.stacks.forEach { retain(collector.accept(it)) }
        definitions.invocations.forEach { retain(collector.accept(it)) }
        retain(collector.finish())
        val profile = TierEProfile(resolved, collector.integrity)
        state = TierESessionState.Finished(profile)
        return profile
    }

    private fun retain(batch: List<ResolvedSyscall>) {
        val accepted = (options.maxRetainedRecords - resolved.size).coerceAtLeast(0).coerceAtMost(batch.size)
        if (accepted > 0) resolved.addAll(batch.subList(0, accepted))
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
            task = TaskIdentity(processEpoch, tid.toInt(), 0),
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
