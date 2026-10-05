package io.mazewall.profiler.internal.hotspot

/** Optional, caller-owned diagnostic timings; never participates in admission decisions. */
internal class HotSpotAssetTimings(
    private val clock: () -> Long = System::nanoTime,
) {
    private val elapsed = linkedMapOf<String, Long>()

    fun <T> measure(
        phase: String,
        action: () -> T,
    ): T {
        val started = clock()
        try {
            return action()
        } finally {
            elapsed[phase] = (elapsed[phase] ?: 0L) + (clock() - started)
        }
    }

    fun snapshot(): Map<String, Long> = java.util.Map.copyOf(elapsed)
}

internal fun <T> measuredAssetPhase(
    timings: HotSpotAssetTimings?,
    phase: String,
    action: () -> T,
): T = if (timings == null) action() else timings.measure(phase, action)
