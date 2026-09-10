package io.mazewall.core

import io.mazewall.MazewallInternal

/** Failure ordering shared by resource owners; it never closes resources or consumes interruption. */
@MazewallInternal
public object CleanupFailures {
    /**
     * Retain the first failure, promoting a later [Error] without losing ordinary evidence.
     * Re-observing the same owner/outcome is idempotent by throwable identity.
     * Interruption timing remains the caller's responsibility: some owners must finish draining
     * with the interrupt flag cleared, while others must propagate immediately.
     */
    public fun merge(
        previous: Throwable?,
        next: Throwable,
    ): Throwable {
        if (previous == null || previous === next) return next
        val primary = if (next is Error && previous !is Error) next else previous
        val secondary = if (primary === next) previous else next
        if (primary.suppressed.none { it === secondary }) primary.addSuppressed(secondary)
        return primary
    }
}
