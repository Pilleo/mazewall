package io.mazewall.enforcer.state

/** Retained installation evidence. Thread proof is never inherited from process bookkeeping. */
internal data class InstallationEvidence(
    val failure: Throwable? = null,
    val cet: CetThreadProof? = null,
)

/** Native LOCK success and enabled-status verification on this exact platform thread/backend. */
internal data class CetThreadProof(
    val nativeIdentity: Any,
    val owner: Thread,
)
