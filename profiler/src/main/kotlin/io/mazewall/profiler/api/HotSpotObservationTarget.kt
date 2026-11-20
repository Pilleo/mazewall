package io.mazewall.profiler.api

import io.mazewall.platform.lifecycle.Pid

/** Independently discovered host identity, not identity learned from collector output. */
public data class HotSpotObservationTarget(
    public val pid: Pid,
    public val processStartTicks: ULong,
    public val cgroupId: ULong,
    public val mountNamespace: ULong,
) {
    init {
        require(pid.value > 0)
        require(processStartTicks > 0uL && cgroupId > 0uL && mountNamespace > 0uL)
    }
}
