package io.mazewall.platform.seccomp.daemon

import io.mazewall.MazewallInternal
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.ffi.networking.SeccompConnection
import java.nio.channels.ClosedByInterruptException
import java.util.concurrent.CancellationException

/** Cleanup status for daemon/session owners; retry uses the original connection. */
@MazewallInternal
public sealed interface SeccompCleanupOutcome {
    public data object Complete : SeccompCleanupOutcome

    public data class Incomplete(
        public val failures: List<Exception>,
        public val retryableOwnership: List<FileDescriptor<*, FdState.Open, FdOwnership.Owned>>,
    ) : SeccompCleanupOutcome
}

/** Interprets close results without discarding failed descriptor ownership. */
@MazewallInternal
public object SeccompCleanupInterpreter {
    public fun close(connection: SeccompConnection): SeccompCleanupOutcome =
        synchronized(connection) {
        when (val result = connection.closeWithResult()) {
            SeccompConnection.CloseResult.Closed -> SeccompCleanupOutcome.Complete
            is SeccompConnection.CloseResult.Failed ->
                SeccompCleanupOutcome.Incomplete(result.failures, connection.retryableOwnership())
        }
    }

    /** Independent releases all run; cancellation propagates after remaining releases. */
    internal fun closeResources(vararg releases: () -> Unit): SeccompCleanupOutcome = closeResources(releases.asList())

    @Suppress("TooGenericExceptionCaught")
    internal fun closeResources(releases: Iterable<() -> Unit>): SeccompCleanupOutcome {
        val failures = mutableListOf<Exception>()
        for (release in releases) {
            try {
                release()
            } catch (failure: Exception) {
                failures += failure
            }
        }
        if (failures.any { it is InterruptedException || it is ClosedByInterruptException }) {
            Thread.currentThread().interrupt()
        }
        failures.filterIsInstance<CancellationException>().firstOrNull()?.let { cancellation ->
            failures.filter { it !== cancellation }.forEach(cancellation::addSuppressed)
            throw cancellation
        }
        return if (failures.isEmpty()) {
            SeccompCleanupOutcome.Complete
        } else {
            SeccompCleanupOutcome.Incomplete(failures.toList(), emptyList())
        }
    }
}
