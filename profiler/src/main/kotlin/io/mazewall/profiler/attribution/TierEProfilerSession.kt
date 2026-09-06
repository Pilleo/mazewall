package io.mazewall.profiler.attribution

import io.mazewall.profiler.tierE.engine.TierEbpfEngine
import io.mazewall.profiler.tierE.ringbuf.SyscallInvocationEvent
import java.nio.file.Path

/** Reusable Kubescape-facing composition of the eBPF engine and JVMTI dictionary. */
public class TierEProfilerSession(
    private val engine: TierEbpfEngine,
    private val processEpoch: Long,
    private val options: TierEOptions = TierEOptions(),
) : AutoCloseable {
    private val collector = AttributionCollector(options.emissionMode)
    private val resolved = mutableListOf<ResolvedSyscall>()
    private var reportedKernelLosses = 0L
    private var reportedMarkerFailures = 0L

    public fun installAndAttach(
        targetPid: Int,
        sessionTag: Int,
        agentLibrary: Path,
        markerOffset: Long,
    ) {
        engine.install(targetPid, sessionTag, options)
        engine.attachMarker(targetPid, agentLibrary, markerOffset)
    }

    /** Non-blocking drain suitable for a dedicated collector thread. */
    public fun poll(): List<ResolvedSyscall> {
        val batch = engine.drainEvents().flatMap { collector.accept(it.toAttribution()) }
        resolved += batch
        val losses = engine.readLossCount()
        check(losses >= reportedKernelLosses) { "kernel loss counter moved backwards" }
        if (losses != reportedKernelLosses) {
            collector.recordLoss(losses - reportedKernelLosses)
            reportedKernelLosses = losses
        }
        val markerFailures = engine.readMarkerFailureCount()
        check(markerFailures >= reportedMarkerFailures) { "marker failure counter moved backwards" }
        if (markerFailures != reportedMarkerFailures) {
            collector.recordLoss(markerFailures - reportedMarkerFailures)
            reportedMarkerFailures = markerFailures
        }
        return batch
    }

    /** Completes delayed ID resolution after the target agent writes its dictionary. */
    public fun finish(definitionFile: Path): TierEProfile {
        poll()
        val definitions = AgentDefinitionFile.read(definitionFile)
        if (definitions.agentLosses > 0) collector.recordLoss(definitions.agentLosses)
        definitions.stacks.forEach { resolved += collector.accept(it) }
        definitions.invocations.forEach { resolved += collector.accept(it) }
        resolved += collector.finish()
        return TierEProfile(resolved.toList(), collector.integrity)
    }

    public fun markerTransitionCount(): Long = engine.readMarkerTransitionCount()

    public fun lastMarkerPidTgid(): Long = engine.readLastMarkerPidTgid()

    override fun close() = engine.close()

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
            // Unmarked tasks have no task-storage sequence; their monotonic
            // kernel timestamp is still an explicit positive observation key.
            taskSequence = sequence.toLong().takeIf { it > 0 } ?: ktimeNs.toLong(),
            syscallNumber = syscallNr,
            args = args.map(ULong::toLong),
            invocationId = active.takeIf { status == AttributionStatus.PENDING_DICTIONARY },
            attributionStatus = status,
            captureFlags = flags,
        )
    }
}

public data class TierEProfile(
    val syscalls: List<ResolvedSyscall>,
    val integrity: SessionIntegrity,
)
