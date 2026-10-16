package io.mazewall.profiler.internal.hotspot

import io.mazewall.profiler.api.BillOfBehavior
import io.mazewall.profiler.api.EbpfLoad
import io.mazewall.profiler.api.ProfileEnvironment
import io.mazewall.profiler.api.ProfileStrategy
import io.mazewall.profiler.api.ProfilingCoverage
import io.mazewall.profiler.api.ProfilingResult
import io.mazewall.profiler.api.StackAttribution

internal object HotSpotCoverageAdapter {
    fun toResult(evidence: StructuredProfileEvidence): ProfilingResult<Unit> {
        return result(evidence, false, true)
    }

    fun toLiveResult(
        evidence: StructuredProfileEvidence,
        parentJoined: Boolean = evidence.finalized,
    ): ProfilingResult<Unit> {
        return result(evidence, true, parentJoined).withStructuredEvidence(
            io.mazewall.profiler.api
            .StructuredProfileEvidence(evidence),
        )
    }

    private fun result(
        evidence: StructuredProfileEvidence,
        live: Boolean,
        parentJoined: Boolean,
    ): ProfilingResult<Unit> {
        val inferred = ProfilingCoverage
            .infer(
            strategy = if (live) ProfileStrategy.HOTSPOT_AGENTLESS else ProfileStrategy.EBPF,
            strategyReason = if (live) "explicit HotSpot process-wide window; selected handler entries include background threads" else "HotSpot schema-1 evidence replay; replay is not live capture",
            processWide = true,
            observations = emptyList(),
            stacks = if (evidence.records.any { it.evidence.attribution == "event_correlated" }) StackAttribution.CAPTURED else StackAttribution.SKIPPED,
            droppedEvents = evidence.summary.observedEventLoss
                ?.coerceAtMost(Int.MAX_VALUE.toULong())
                ?.toInt() ?: 0,
            drainComplete = evidence.summary.physicalDrainComplete && parentJoined,
            environment = ProfileEnvironment(
                if (live) System.getProperty("os.version") else "recorded evidence",
                EbpfLoad.Denied(if (live) "parent authority unprobed; external helper admits actual authority/runtime" else "replay does not probe collector authority"),
            ),
        )
        val coverage = inferred.withWarnings(
            inferred.warnings +
                listOf("selected openat handler entries are not complete syscall/policy evidence", "complete native loss is unknown; droppedEvents is only an observed lower bound") +
                if (evidence.retentionComplete) emptyList() else listOf("Kotlin evidence retention is incomplete"),
            complete = false,
        )
        return ProfilingResult(Unit, BillOfBehavior(), emptyMap(), coverage)
    }
}
