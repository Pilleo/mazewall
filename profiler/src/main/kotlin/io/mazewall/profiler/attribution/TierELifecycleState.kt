package io.mazewall.profiler.attribution

/** Internal session lifecycle; attribution cannot reopen after CLOSING. */
internal enum class TierESessionLifecycleState {
    ATTACHING,
    RUNNING,
    CLOSING,
    DEAD,
}
