package io.mazewall.portal

/** Read-only local diagnostic state for one isolation group. */
public data class PortalServiceHealth(
    val isolationGroup: String,
    val instanceMode: PortalInstanceMode,
    val state: PortalServiceHealthState,
    /** Name from the generated manifest, resolved through the host runtime registry. */
    val policyProfile: String? = null,
    /** Configured resource contract; null only for legacy direct broker diagnostics. */
    val workerProfile: PortalWorkerProfile? = null,
    /** Read-only cgroup samples for workers currently owned by this runtime. */
    val cgroupUsage: List<PortalCgroupUsage> = emptyList(),
    /** Most recent local worker transition observed by the broker. */
    val lastWorkerLifecycle: PortalWorkerLifecycle = PortalWorkerLifecycle.notStarted(),
    /** Live worker PIDs known by this local runtime; empty before lazy startup or after loss. */
    val workerPids: List<Long> = emptyList(),
    /** Most recent local timing, if the group has completed an invocation. */
    val lastInvocation: PortalInvocationTiming? = null,
)

public enum class PortalServiceHealthState {
    AVAILABLE,
    STATE_LOST,
}

public data class PortalWorkerLifecycle(
    public val state: PortalWorkerLifecycleState,
    public val pid: Long? = null,
    public val detail: String? = null,
) {
    public companion object {
        internal fun notStarted(): PortalWorkerLifecycle = PortalWorkerLifecycle(PortalWorkerLifecycleState.NOT_STARTED)
    }
}

public enum class PortalWorkerLifecycleState {
    NOT_STARTED,
    STARTED,
    STARTUP_FAILED,
    RETIRED,
    CLOSED,
}
