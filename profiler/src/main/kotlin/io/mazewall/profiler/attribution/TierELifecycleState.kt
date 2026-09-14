@file:Suppress("MatchingDeclarationName") // the file groups all Tier E lifecycle states by subsystem

package io.mazewall.profiler.attribution

/** Internal session lifecycle; attribution cannot reopen after CLOSING. */
internal enum class TierESessionLifecycleState {
    ATTACHING,
    RUNNING,
    CLOSING,
    DEAD,
}
