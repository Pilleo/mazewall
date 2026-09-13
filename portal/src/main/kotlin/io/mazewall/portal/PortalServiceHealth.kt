package io.mazewall.portal

/** Read-only local diagnostic state for one isolation group. */
public data class PortalServiceHealth(
    val isolationGroup: String,
    val instanceMode: PortalInstanceMode,
    val state: PortalServiceHealthState,
    /** Live worker PIDs known by this local runtime; empty before lazy startup or after loss. */
    val workerPids: List<Long> = emptyList(),
    /** Most recent local timing, if the group has completed an invocation. */
    val lastInvocation: PortalInvocationTiming? = null,
)

public enum class PortalServiceHealthState {
    AVAILABLE,
    STATE_LOST,
}
