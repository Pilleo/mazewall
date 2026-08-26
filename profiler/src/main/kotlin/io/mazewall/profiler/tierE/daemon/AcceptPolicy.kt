package io.mazewall.profiler.tierE.daemon

import io.mazewall.profiler.tierE.ffi.PosixFfi
import io.mazewall.profiler.tierE.ffi.Ucred

/**
 * Pure accept-decision logic — no I/O, no side effects, fully unit-testable.
 */
public sealed interface AcceptDecision {
    public data object Accept : AcceptDecision
    public data class Reject(public val reply: String) : AcceptDecision
}

public fun decideAccept(
    sessionActive: Boolean,
    cred: Ucred?,
): AcceptDecision = when {
    sessionActive -> AcceptDecision.Reject("ERR BUSY")
    cred == null -> AcceptDecision.Reject("ERR PEER_UID -1")
    cred.uid != 0 -> AcceptDecision.Reject("ERR PEER_UID ${cred.uid}")
    else -> AcceptDecision.Accept
}
