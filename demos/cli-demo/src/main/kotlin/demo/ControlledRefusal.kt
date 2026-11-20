package demo

import io.mazewall.enforcer.api.ContainmentViolationEvidence
import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.enforcer.diagnostics.ContainmentViolationDetector
import java.io.IOException
import java.net.SocketException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

internal enum class ControlledOperation {
    PROCESS_LAUNCH,
    NETWORK_CONNECT,
}

/** Only the private, positively controlled CLI fixtures opt into inferred permission refusals. */
internal fun observeControlledRefusal(
    name: String,
    operation: ControlledOperation,
    action: () -> String,
): ScenarioResult {
    val outcome = try {
        ScenarioOutcome.Completed(action())
    } catch (failure: Exception) {
        rethrowFatalOrRestoreInterruption(failure)
        controlledRefusal(failure, operation)
    }
    return ScenarioResult(name, ExpectedResult.PERMISSION_REFUSED, outcome)
}

private fun controlledRefusal(failure: Exception, operation: ControlledOperation): ScenarioOutcome {
    val infrastructure = ScenarioOutcome.InfrastructureFailure(failure)
    if (failure !is ContainmentViolationException) return infrastructure
    val causes = mutableListOf<Throwable>()
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = failure
    while (current != null) {
        // A delivery denial cannot excuse an incomplete end wait or failed owner cleanup.
        if (!seen.add(current) || current.suppressed.isNotEmpty() ||
            current is TimeoutException || current is InterruptedException || current is CancellationException ||
            current is ExecutionException || current is CompletionException
        ) return infrastructure
        causes += current
        current = current.cause
    }
    return when (failure.evidence) {
        ContainmentViolationEvidence.OBSERVED_POLICY_DENIAL -> ScenarioOutcome.Denied(failure.evidence.name)
        ContainmentViolationEvidence.UNKNOWN -> infrastructure
        ContainmentViolationEvidence.INFERRED_PERMISSION_FAILURE -> {
            val expectedCause = when (operation) {
                ControlledOperation.PROCESS_LAUNCH -> causes.any {
                    it is IOException && processPermissionErrno.containsMatchIn(it.message.orEmpty())
                }
                ControlledOperation.NETWORK_CONNECT -> causes.any {
                    it is SocketException &&
                        ContainmentViolationDetector.diagnose(it)?.evidence == ContainmentViolationEvidence.INFERRED_PERMISSION_FAILURE
                }
            }
            if (expectedCause) ScenarioOutcome.PermissionRefused(failure) else infrastructure
        }
    }
}

private val processPermissionErrno = Regex("\\berror[=:]\\s*(1|13)\\b")
