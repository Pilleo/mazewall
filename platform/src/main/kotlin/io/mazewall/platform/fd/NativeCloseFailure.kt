package io.mazewall.platform.fd

/**
 * A native close(2) result failed; the integer may already be reused and must never be
 * retried. Internal to [FileDescriptor]; the relocated copies in :lockdown and other
 * curated modules consume it without the MazewellInternal protocol marker.
 */
internal class NativeCloseFailure internal constructor(
    message: String,
) : IllegalStateException(message)
