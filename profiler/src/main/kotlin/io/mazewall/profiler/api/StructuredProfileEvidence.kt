package io.mazewall.profiler.api

import io.mazewall.profiler.internal.hotspot.HotSpotEvidenceDocument
import io.mazewall.profiler.internal.hotspot.HotSpotFrame
import io.mazewall.profiler.internal.hotspot.HotSpotOccurrence
import io.mazewall.profiler.internal.hotspot.HotSpotProvider
import io.mazewall.profiler.internal.hotspot.HotSpotReplaySummary
import io.mazewall.profiler.internal.hotspot.HotSpotStackEvidence
import io.mazewall.profiler.internal.hotspot.StructuredProfileEvidence as InternalEvidence

/** Immutable selected-event diagnostics. Physical finalization is independent of collection completeness. */
public class StructuredProfileEvidence internal constructor(
    internal val owner: InternalEvidence,
) {
    public val sessionId: String = owner.sessionId
    public val records: List<StructuredProfileOccurrence> = java.util.List.copyOf(owner.records.map(::StructuredProfileOccurrence))
    public val summary: StructuredProfileSummary = StructuredProfileSummary(owner.summary)
    public val discardedOccurrences: ULong = owner.discardedOccurrences
    public val retainedBytes: Int = owner.retainedBytes
    public val recoveries: List<String> = owner.recoveries
    public val discardedRecoveries: ULong = owner.discardedRecoveries
    public val pendingOccurrences: ULong = owner.pendingOccurrences
    public val finalized: Boolean = owner.finalized
    public val collectionLossComplete: Boolean = false
    public val terminalAccountingComplete: Boolean = owner.terminalAccountingComplete
    public val retentionComplete: Boolean = owner.retentionComplete

    /** Bounded diagnostic report; it is not a policy certificate or native-resource attestation. */
    public fun toJson(): String = HotSpotEvidenceDocument.encode(owner)

    public companion object {
        @JvmStatic
        public fun fromJson(json: String): StructuredProfileEvidence = StructuredProfileEvidence(HotSpotEvidenceDocument.decode(json))
    }
}

public class StructuredProfileOccurrence internal constructor(
    value: HotSpotOccurrence,
) {
    public val eventId: String = value.eventId
    public val event: StructuredProfileEvent = StructuredProfileEvent(value.event)
    public val evidence: StructuredProfileStackEvidence = StructuredProfileStackEvidence(value.evidence)
}

public class StructuredProfileStackEvidence internal constructor(
    private val value: HotSpotStackEvidence,
) {
    public val attribution: String = value.attribution
    public val walk: String = value.walk
    public val scopeLookup: String = value.scopeLookup
    public val reason: String? = value.reason
    public val provider: StructuredProfileProvider? = value.provider?.let(::StructuredProfileProvider)
    public val frames: List<StructuredProfileFrame> = java.util.List.copyOf(value.frames.map(::StructuredProfileFrame))
    public val rawErrors: List<String> = value.rawErrors
    public val rawJson: String get() = value.rawJson
}

public class StructuredProfileProvider internal constructor(
    value: HotSpotProvider,
) {
    public val name: String = value.name
    public val gadgetRevision: String = value.gadgetRevision
    public val profilerRevision: String = value.profilerRevision
    public val patchSha256: String = value.patchSha256
}

/** Leaf/root order, unsigned provider positions and optional source/BCI domains are preserved verbatim. */
public class StructuredProfileFrame internal constructor(
    private val value: HotSpotFrame,
) {
    public val providerType: UInt = value.providerType
    public val frameType: String = value.frameType
    public val functionName: String = value.functionName
    public val functionOffset: UInt? = value.functionOffset
    public val sourceFile: String? = value.sourceFile
    public val sourceLine: UInt? = value.sourceLine
    public val sourceColumn: UInt? = value.sourceColumn
    public val nativeAddress: ULong? = value.nativeAddress
    public val bci: UInt? = value.bci
    public val rawBci: UInt? = value.rawBci
    public val rawPosition: ULong = value.rawPosition
    public val positionDomain: String = value.positionDomain
    public val errorFrame: Boolean = value.errorFrame
    public val scopeLookup: String? = value.scopeLookup
    public val scopeWalk: String? = value.scopeWalk
    public val bciState: String? = value.bciState
    public val qualityDiagnostic: String? = value.qualityDiagnostic
    public val rawJson: String get() = value.rawJson
}

public class StructuredProfileSummary internal constructor(
    value: HotSpotReplaySummary,
) {
    public val received: ULong = value.received
    public val resolved: ULong = value.resolved
    public val unavailable: ULong = value.unavailable
    public val skipped: ULong = value.skipped
    public val terminalAccountingComplete: Boolean = value.terminalAccountingComplete
    public val physicalDrainComplete: Boolean = value.physicalDrainComplete
    public val observedEventLoss: ULong? = value.observedEventLoss
    public val rawJson: String = value.rawJson
}
