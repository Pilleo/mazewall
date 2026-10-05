package io.mazewall.profiler.internal.hotspot

import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.lifecycle.Tid

private const val MAX_LINE_BYTES = 1048576
private const val MAX_WIRE_RECORDS = 65536
private const val MAX_TRACKED_OCCURRENCES = 32768
private const val MAX_FRAMES = 4096
private const val MAX_RETAINED_OCCURRENCES = 4096
private const val MAX_RETAINED_BYTES = 33554432

internal data class HotSpotReplayLimits(
    val maxLineBytes: Int = MAX_LINE_BYTES,
    val maxWireRecords: Int = MAX_WIRE_RECORDS,
    val maxTrackedOccurrences: Int = MAX_TRACKED_OCCURRENCES,
    val maxFramesPerEvidence: Int = MAX_FRAMES,
    val maxRetainedOccurrences: Int = MAX_RETAINED_OCCURRENCES,
    val maxRetainedBytes: Int = MAX_RETAINED_BYTES,
) {
    init {
        require(maxLineBytes in 1..MAX_LINE_BYTES)
        require(maxWireRecords in 1..MAX_WIRE_RECORDS)
        require(maxTrackedOccurrences in 1..MAX_TRACKED_OCCURRENCES)
        require(maxFramesPerEvidence in 1..MAX_FRAMES)
        require(maxRetainedOccurrences in 0..MAX_RETAINED_OCCURRENCES)
        require(maxRetainedBytes in 0..MAX_RETAINED_BYTES)
    }
}

internal class HotSpotProtocolException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

internal fun checkHotSpotProtocol(
    condition: Boolean,
    message: String,
) {
    if (!condition) throw HotSpotProtocolException(message)
}

internal data class HotSpotEvent(
    val pid: Pid,
    val tid: Tid,
    val processStartTicks: ULong,
    val captureBootNanos: ULong,
    val correlationId: ULong,
    val filename: String,
    val pathTruncated: Boolean,
    private val rawValue: HotSpotRawJson,
) {
    val rawJson: String get() = rawValue.text
}

internal data class HotSpotFrame(
    val providerType: UInt,
    val frameType: String,
    val functionName: String,
    val functionOffset: UInt?,
    val sourceFile: String?,
    val sourceLine: UInt?,
    val sourceColumn: UInt?,
    val nativeAddress: ULong?,
    val bci: UInt?,
    val rawBci: UInt?,
    val rawPosition: ULong,
    val positionDomain: String,
    val errorFrame: Boolean,
    val scopeLookup: String?,
    val scopeWalk: String?,
    val bciState: String?,
    val qualityDiagnostic: String?,
    private val rawValue: HotSpotRawJson,
) {
    val rawJson: String get() = rawValue.text
}

internal data class HotSpotProvider(
    val name: String,
    val gadgetRevision: String,
    val profilerRevision: String,
    val patchSha256: String,
)

internal class HotSpotStackEvidence(
    val attribution: String,
    val walk: String,
    val scopeLookup: String,
    val reason: String?,
    val provider: HotSpotProvider?,
    frames: List<HotSpotFrame>,
    rawErrors: List<String>,
    private val rawValue: HotSpotRawJson,
) {
    val rawJson: String get() = rawValue.text
    val frames: List<HotSpotFrame> = java.util.List.copyOf(frames)
    val rawErrors: List<String> = java.util.List.copyOf(rawErrors)
}

internal data class HotSpotOccurrence(
    val eventId: String,
    val event: HotSpotEvent,
    val evidence: HotSpotStackEvidence,
)

internal data class HotSpotReplaySummary(
    val received: ULong,
    val resolved: ULong,
    val unavailable: ULong,
    val skipped: ULong,
    val terminalAccountingComplete: Boolean,
    val physicalDrainComplete: Boolean,
    val observedEventLoss: ULong?,
    val rawJson: String,
)

internal class StructuredProfileEvidence(
    val sessionId: String,
    records: List<HotSpotOccurrence>,
    val summary: HotSpotReplaySummary,
    val discardedOccurrences: ULong,
    val retainedBytes: Int,
    recoveries: List<String>,
    val discardedRecoveries: ULong,
    val finalized: Boolean = true,
    val pendingOccurrences: ULong = 0uL,
) {
    val records: List<HotSpotOccurrence> = java.util.List.copyOf(records)
    val recoveries: List<String> = java.util.List.copyOf(recoveries)
    val collectionLossComplete: Boolean = false
    val terminalAccountingComplete: Boolean get() = summary.terminalAccountingComplete
    val retentionComplete: Boolean get() = discardedOccurrences == 0uL && discardedRecoveries == 0uL
}
