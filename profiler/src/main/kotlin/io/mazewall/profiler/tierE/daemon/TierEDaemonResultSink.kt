package io.mazewall.profiler.tierE.daemon

import io.mazewall.profiler.internal.attribution.TierECollectorRuntimeResult

/** Local post-reply sink for immutable Tier E terminal evidence. */
internal fun interface TierEDaemonResultSink {
    fun accept(result: TierECollectorRuntimeResult)
}

/** Integration seam for node agents and other external report stores. */
internal fun interface TierETerminalReportSink {
    fun accept(report: TierETerminalReport)
}
