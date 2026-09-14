package io.mazewall.platform.seccomp.daemon

import io.mazewall.platform.lifecycle.MazewallInternal
import io.mazewall.platform.seccomp.UserNotifReplyResult
import java.util.concurrent.atomic.AtomicReference

/** Process-local cumulative counters. Values saturate at [Long.MAX_VALUE]; no identities are retained. */
@MazewallInternal
internal data class NotificationLivenessSnapshot(
    public val started: Long = 0,
    public val deadlineExpiries: Long = 0,
    public val sent: Long = 0,
    public val notificationGone: Long = 0,
    public val failed: Long = 0,
    public val lateCompletions: Long = 0,
    public val pending: Long = 0,
    public val oldestPendingAgeNanos: Long = 0,
    public val deadlineNear: Long = 0,
    public val replyLatencyCount: Long = 0,
    public val replyLatencyNanos: Long = 0,
    public val maxReplyLatencyNanos: Long = 0,
    public val reactorPolls: Long = 0,
    public val helperSaturation: Long = 0,
)

/** Internal diagnostics for this daemon process; obtaining a snapshot never resets counters. */
@MazewallInternal
internal object NotificationLivenessMetrics {
    internal val counters = NotificationLivenessCounters()

    public fun snapshot(): NotificationLivenessSnapshot = counters.snapshot()
}

/** A single atomic publication makes each immutable snapshot consistent under concurrent sessions. */
internal class NotificationLivenessCounters(
    initial: NotificationLivenessSnapshot = NotificationLivenessSnapshot(),
) {
    private val state = AtomicReference(initial)

    fun snapshot(): NotificationLivenessSnapshot = state.get()

    fun started() {
        state.updateAndGet {
            it.copy(
                started = it.started.increment(),
                pending = it.pending.increment(),
            )
        }
    }

    fun finished() {
        state.updateAndGet { it.copy(pending = (it.pending - 1).coerceAtLeast(0)) }
    }

    fun reactorPoll() {
        state.updateAndGet { it.copy(reactorPolls = it.reactorPolls.increment()) }
    }

    fun observePending(
        ageNanos: Long,
        deadlineNear: Boolean,
    ) {
        state.updateAndGet {
            it.copy(
                oldestPendingAgeNanos = maxOf(it.oldestPendingAgeNanos, ageNanos.coerceAtLeast(0)),
                deadlineNear = if (deadlineNear) it.deadlineNear.increment() else it.deadlineNear,
            )
        }
    }

    fun replyLatency(durationNanos: Long) {
        val nonNegative = durationNanos.coerceAtLeast(0)
        state.updateAndGet {
            it.copy(
                replyLatencyCount = it.replyLatencyCount.increment(),
                replyLatencyNanos = it.replyLatencyNanos.saturatingAdd(nonNegative),
                maxReplyLatencyNanos = maxOf(it.maxReplyLatencyNanos, nonNegative),
            )
        }
    }

    fun helperSaturated() {
        state.updateAndGet { it.copy(helperSaturation = it.helperSaturation.increment()) }
    }

    fun expired(lateCompletion: Boolean) {
        state.updateAndGet {
            it.copy(
                deadlineExpiries = it.deadlineExpiries.increment(),
                lateCompletions = if (lateCompletion) it.lateCompletions.increment() else it.lateCompletions,
            )
        }
    }

    fun reply(outcome: UserNotifReplyResult) {
        state.updateAndGet {
            when (outcome) {
                UserNotifReplyResult.Sent -> it.copy(sent = it.sent.increment())
                UserNotifReplyResult.NotificationGone -> it.copy(notificationGone = it.notificationGone.increment())
                is UserNotifReplyResult.Failed -> it.copy(failed = it.failed.increment())
            }
        }
    }

    fun replyThrew() {
        state.updateAndGet { it.copy(failed = it.failed.increment()) }
    }

    private fun Long.increment(): Long = if (this == Long.MAX_VALUE) this else this + 1

    private fun Long.saturatingAdd(other: Long): Long = if (this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other
}
