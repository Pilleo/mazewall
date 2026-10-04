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
        val inferred = ProfilingCoverage
            .infer(
            strategy = ProfileStrategy.EBPF,
            strategyReason = "HotSpot schema-1 evidence replay; live strategy dispatch is not installed",
            processWide = true,
            observations = emptyList(),
            stacks = if (evidence.records.any { it.evidence.attribution == "event_correlated" }) StackAttribution.CAPTURED else StackAttribution.SKIPPED,
            droppedEvents = evidence.summary.observedEventLoss
                ?.coerceAtMost(Int.MAX_VALUE.toULong())
                ?.toInt() ?: 0,
            drainComplete = evidence.summary.physicalDrainComplete,
            environment = ProfileEnvironment("recorded evidence", EbpfLoad.Denied("replay does not probe collector authority")),
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
