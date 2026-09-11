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

    internal fun closeResources(
        releases: Iterable<() -> Unit>,
        recordOutcome: (SeccompCleanupOutcome) -> Unit = { },
    ): SeccompCleanupOutcome {
        val captured = captureFailures(releases)
        val outcome = cleanupOutcome(captured.failures, captured.fatal)
        publishOutcome(recordOutcome, outcome, captured.fatal)
        restoreInterrupt(captured.failures)
        throwPrimary(captured.failures, captured.fatal)
        return outcome
    }

    private data class CapturedFailures(
        val failures: List<Exception>,
        val fatal: Error?,
    )

    private fun captureFailures(releases: Iterable<() -> Unit>): CapturedFailures {
        val failures = mutableListOf<Exception>()
        var fatal: Error? = null
        releases.forEach { release ->
            val failure = runCatching { release() }.exceptionOrNull() ?: return@forEach
            when (failure) {
                is Error -> if (fatal == null) {
                    fatal = failure
                } else if (failure !== fatal) {
                    fatal.addSuppressed(failure)
                }
                is Exception -> failures += failure
                else -> failures += Exception("Non-standard cleanup failure", failure)
            }
        }
        return CapturedFailures(failures, fatal)
    }

    private fun cleanupOutcome(
        failures: List<Exception>,
        fatal: Error?,
    ): SeccompCleanupOutcome {
        val reported = fatal?.let { failures + FatalCleanupFailureException(it) } ?: failures
        return if (reported.isEmpty()) SeccompCleanupOutcome.Complete else SeccompCleanupOutcome.Incomplete(reported, emptyList())
    }

    private fun publishOutcome(
        record: (SeccompCleanupOutcome) -> Unit,
        outcome: SeccompCleanupOutcome,
        fatal: Error?,
    ) {
        val recordFailure = runCatching { record(outcome) }.exceptionOrNull() ?: return
        if (fatal == null || recordFailure === fatal) throw recordFailure
        fatal.addSuppressed(recordFailure)
    }

    private fun restoreInterrupt(failures: List<Exception>) {
        if (failures.any { it is InterruptedException || it is ClosedByInterruptException }) Thread.currentThread().interrupt()
    }

    private fun throwPrimary(
        failures: List<Exception>,
        fatal: Error?,
    ) {
        fatal?.let { error ->
            failures.forEach(error::addSuppressed)
            throw error
        }
        failures.filterIsInstance<CancellationException>().firstOrNull()?.let { cancellation ->
            failures.filter { it !== cancellation }.forEach(cancellation::addSuppressed)
            throw cancellation
        }
    }

    private class FatalCleanupFailureException(
        failure: Throwable,
    ) : Exception("Fatal cleanup release failed", failure)
}
