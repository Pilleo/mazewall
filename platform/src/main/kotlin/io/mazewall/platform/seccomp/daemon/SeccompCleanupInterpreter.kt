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
    public fun close(connection: SeccompConnection): SeccompCleanupOutcome = close(connection) { }

    /** Publishes the outcome to its owner before cancellation propagates or interruption is restored. */
    public fun close(
        connection: SeccompConnection,
        recordOutcome: (SeccompCleanupOutcome) -> Unit,
    ): SeccompCleanupOutcome =
        synchronized(connection) {
        val result = connection.closeWithResult { recordOutcome(outcome(connection, it)) }
        outcome(connection, result)
    }

    private fun outcome(
        connection: SeccompConnection,
        result: SeccompConnection.CloseResult,
    ): SeccompCleanupOutcome =
        when (result) {
            SeccompConnection.CloseResult.Closed -> SeccompCleanupOutcome.Complete
            is SeccompConnection.CloseResult.Failed ->
                SeccompCleanupOutcome.Incomplete(result.failures, connection.retryableOwnership())
        }

    /** Independent releases all run; cancellation propagates after remaining releases. */
    internal fun closeResources(vararg releases: () -> Unit): SeccompCleanupOutcome = closeResources(releases.asList())

    @Suppress("TooGenericExceptionCaught")
    internal fun closeResources(
        releases: Iterable<() -> Unit>,
        recordOutcome: (SeccompCleanupOutcome) -> Unit = { },
    ): SeccompCleanupOutcome {
        val failures = mutableListOf<Exception>()
        var fatalFailure: Throwable? = null
        for (release in releases) {
            try {
                release()
            } catch (failure: Throwable) {
                if (failure is Exception) {
                    failures += failure
                } else if (fatalFailure == null) {
                    fatalFailure = failure
                } else if (failure !== fatalFailure) {
                    fatalFailure.addSuppressed(failure)
                }
            }
        }
        val reportedFailures =
            fatalFailure?.let { failures + FatalCleanupFailureException(it) }
                ?: failures.toList()
        val outcome = if (reportedFailures.isEmpty()) {
            SeccompCleanupOutcome.Complete
        } else {
            SeccompCleanupOutcome.Incomplete(reportedFailures, emptyList())
        }
        try {
            recordOutcome(outcome)
        } catch (recordFailure: Throwable) {
            val fatal = fatalFailure
            if (fatal == null || recordFailure === fatal) throw recordFailure
            fatal.addSuppressed(recordFailure)
        }
        if (failures.any { it is InterruptedException || it is ClosedByInterruptException }) {
            Thread.currentThread().interrupt()
        }
        fatalFailure?.let { fatal ->
            failures.filter { it !== fatal }.forEach(fatal::addSuppressed)
            throw fatal
        }
        failures.filterIsInstance<CancellationException>().firstOrNull()?.let { cancellation ->
            failures.filter { it !== cancellation }.forEach(cancellation::addSuppressed)
            throw cancellation
        }
        return outcome
    }

    private class FatalCleanupFailureException(
        failure: Throwable,
    ) : Exception("Fatal cleanup release failed", failure)
}
