package io.mazewall.enforcer.api

/** Shadow-stack evidence for the installing platform thread; never process-wide CET or IBT attestation. */
public enum class CetOutcome {
    NOT_REQUESTED,
    UNCONFIRMED,
    VERIFIED_CURRENT_THREAD,
}
