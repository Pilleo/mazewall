package io.mazewall.portal

/** Immutable finite host-approved worker, admission, deadline and protocol bounds. */
public data class PortalRuntimeLimits(
    public val workers: Int = 1,
    public val maxAdmissions: Int = 64,
    public val callTimeoutMs: Long = 30_000L,
    public val startupTimeoutMs: Long = 30_000L,
    public val closeTimeoutMs: Long = 30_000L,
    public val maxRequestBytes: Int = PortalFrame.MAX_PAYLOAD,
    public val maxResponseBytes: Int = PortalFrame.MAX_PAYLOAD,
    public val maxCapabilities: Int = PortalFrame.MAX_FDS,
) {
    init {
        require(workers > 0 && maxAdmissions > 0) { "Portal capacity must be positive" }
        require(callTimeoutMs > 0L && startupTimeoutMs > 0L && closeTimeoutMs > 0L) { "Portal deadlines must be positive" }
        require(maxRequestBytes in 1..PortalFrame.MAX_PAYLOAD && maxResponseBytes in 1..PortalFrame.MAX_PAYLOAD) { "Payload limits exceed protocol bounds" }
        require(maxCapabilities in 0..PortalFrame.MAX_FDS) { "Capability limit exceeds protocol bounds" }
    }
}
