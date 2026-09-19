package io.mazewall.profiler.tierE.daemon

/** The validated marker backend selected for one daemon attachment request. */
internal data class MarkerTransportSelection(
    val requested: AttachMode,
    val selected: AttachMode,
    val usdtNote: UsdtProbeNote?,
    val fallbackReason: String?,
)

/**
 * Resolves a requested marker backend without attaching anything.
 * Explicit USDT requests fail closed; only AUTO may select the plain-uProbe compatibility path.
 */
internal fun selectMarkerTransport(
    requested: AttachMode,
    findStackMarkerV1: () -> UsdtProbeNote,
): MarkerTransportSelection =
    when (requested) {
        AttachMode.UPROBE -> MarkerTransportSelection(requested, AttachMode.UPROBE, null, null)
        AttachMode.USDT -> MarkerTransportSelection(requested, AttachMode.USDT, findStackMarkerV1(), null)
        AttachMode.AUTO -> runCatching(findStackMarkerV1).fold(
            onSuccess = { note -> MarkerTransportSelection(requested, AttachMode.USDT, note, null) },
            onFailure = { failure ->
                MarkerTransportSelection(
                    requested,
                    AttachMode.UPROBE,
                    null,
                    failure.message ?: "usdt_note_validation_failed",
                )
            },
        )
    }
