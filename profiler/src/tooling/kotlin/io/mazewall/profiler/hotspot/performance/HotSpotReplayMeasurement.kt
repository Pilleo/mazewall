package io.mazewall.profiler.hotspot.performance

import io.mazewall.profiler.internal.hotspot.HotSpotEvidenceAssembler
import io.mazewall.profiler.internal.hotspot.HotSpotReplayLimits
import io.mazewall.profiler.internal.hotspot.HotSpotWireReader
import io.mazewall.profiler.internal.hotspot.StructuredProfileEvidence
import java.io.InputStream

internal data class HotSpotReplayMeasurement(
    val evidence: StructuredProfileEvidence,
    val wireRecords: Int,
    val pipelineNanos: Long,
    val mappingNanos: Long,
    val finishNanos: Long,
)

/** Offline schema-1 projection only: timings cannot certify a native lifecycle. */
internal fun measureReplay(
    input: InputStream,
    limits: HotSpotReplayLimits,
): HotSpotReplayMeasurement {
    val accumulator = HotSpotEvidenceAssembler(limits).begin()
    var records = 0
    var mappingNanos = 0L
    val started = System.nanoTime()
    HotSpotWireReader(limits).read(input) { row ->
        val mappingStarted = System.nanoTime()
        accumulator.accept(row)
        mappingNanos += System.nanoTime() - mappingStarted
        records++
    }
    val pipelineNanos = System.nanoTime() - started
    val finishStarted = System.nanoTime()
    val evidence = accumulator.finish()
    val finishNanos = System.nanoTime() - finishStarted
    return HotSpotReplayMeasurement(evidence, records, pipelineNanos, mappingNanos, finishNanos)
}
