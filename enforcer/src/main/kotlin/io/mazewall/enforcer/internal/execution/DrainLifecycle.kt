package io.mazewall.enforcer.internal.execution

import io.mazewall.enforcer.api.execution.DrainReport

/** Immutable observation: physical producer completion is established before sampling failures. */
internal data class DomainDrainSnapshot(
    val retired: Boolean,
    val liveWorkers: Int,
    val unfinished: Int,
    val cleanupFailure: Throwable?,
)

/** Pure completion decision. A pending observation can only produce a conservative Incomplete. */
internal object DrainLifecycle {
    fun evaluate(
        managerStopped: Boolean,
        managerLive: Boolean,
        domains: List<DomainDrainSnapshot>,
    ): DrainReport {
        val failure = domains.firstNotNullOfOrNull { it.cleanupFailure }
        val live = domains.sumOf { it.liveWorkers } + if (managerLive) 1 else 0
        val unfinished = domains.sumOf { it.unfinished }
        return if (managerStopped && domains.all { it.retired } && failure == null && live == 0 && unfinished == 0) {
            DrainReport.Complete
        } else {
            DrainReport.Incomplete(unfinished, live, failure)
        }
    }
}
