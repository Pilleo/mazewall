package io.mazewall.platform.seccomp.daemon

import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.lifecycle.MazewallInternal
import io.mazewall.platform.seccomp.UserNotifReplyResult
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reactor-confined work. Each poll must return after bounded local work or one nonblocking
 * I/O attempt. Pending helpers are inspected without waiting. No callback may send a reply.
 * Iteration-arena segments must not be retained across polls. Close cancels admission and
 * arranges disposal of late results; it must not wait for arbitrary code to finish.
 */
@MazewallInternal
internal interface NotificationWork : AutoCloseable {
    /**
     * Registers the reactor-owned completion signal before the first [poll].
     * Worker threads may signal readiness, but only the reactor may claim a reply lease.
     */
    fun bindCompletionWakeup(wakeup: NotificationCompletionWakeup) {}

    context(arena: NativeArena) public fun poll(): NotificationProgress

    override fun close() {}
}

/** A bounded, session-local signal used only to wake the reactor after preparation completes. */
internal fun interface NotificationCompletionWakeup {
    fun signal()
}

@MazewallInternal
internal sealed interface NotificationProgress {
    public data object Await : NotificationProgress

    /** Only the reactor invokes [reply], after claiming its single terminal lease. */
    public class Ready(
        public val reply: () -> UserNotifReplyResult,
        public val result: NotifResult = NotifResult.HANDLED,
        public val onReply: (UserNotifReplyResult) -> Unit = {},
    ) : NotificationProgress
}

/** A failed/gone reply still consumes the lease; it can never be followed by denial. */
internal class NotificationReplyLease {
    private val claimed = AtomicBoolean(false)

    fun claim(): Boolean = claimed.compareAndSet(false, true)
}
