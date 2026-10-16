package io.mazewall.profiler.api

import io.mazewall.profiler.internal.hotspot.HotSpotAttachOptions
import io.mazewall.profiler.internal.hotspot.HotSpotCollectorTiming
import io.mazewall.profiler.internal.hotspot.HotSpotReplayLimits
import java.nio.file.Path
import java.time.Duration

private const val READINESS_SECONDS = 10L
private const val SESSION_SECONDS = 60L
private const val RETAINED_OCCURRENCES = 4096
private const val RETAINED_BYTES = 33554432

/** Explicit trusted local assets and operator-owned launch authority. Runtime admission is always strict. */
public class HotSpotOptions(
    public val assetsDirectory: Path,
    collectorArgvPrefix: List<String> = emptyList(),
    public val readinessTimeout: Duration = Duration.ofSeconds(READINESS_SECONDS),
    public val sessionDuration: Duration = Duration.ofSeconds(SESSION_SECONDS),
    public val finishTimeout: Duration = Duration.ofSeconds(2),
    public val maxRetainedOccurrences: Int = RETAINED_OCCURRENCES,
    public val maxRetainedBytes: Int = RETAINED_BYTES,
) {
    public val collectorArgvPrefix: List<String> = java.util.List.copyOf(collectorArgvPrefix)
    internal val internalOptions: HotSpotAttachOptions = HotSpotAttachOptions(
        assetsDirectory,
        this.collectorArgvPrefix,
        HotSpotCollectorTiming(readinessTimeout.toNanos(), sessionDuration.toNanos(), finishTimeout.toNanos()),
        HotSpotReplayLimits(maxRetainedOccurrences = maxRetainedOccurrences, maxRetainedBytes = maxRetainedBytes),
    )
}
