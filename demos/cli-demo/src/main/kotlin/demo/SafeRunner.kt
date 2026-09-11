package demo

import io.mazewall.Policy
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.ContainmentViolationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

object SafeRunner {
    internal val policy =
        Policy.builder()
            .base(Policy.NO_EXEC)
            .base(Policy.NO_NETWORK)
            .build()

    fun run(payload: String) = runContained { VulnerableLogger.log(payload) }

    internal fun runContained(action: () -> String) {
        val executor = Executors.newSingleThreadExecutor()
        try {
            // Put wrapper creation inside the owned submission boundary so even a
            // seccomp-install failure retires the raw executor before returning.
            OwnedActionRunner.run(executor) {
                val safeExecutor = ContainedExecutors.wrap(executor, policy)
                safeExecutor.submit<String> { action() }
            }
        } catch (expected: ExecutionException) {
            // We intentionally unwrap the ExecutionException to expose the underlying
            // ContainmentViolationException to the caller for clearer demo output.
            if (expected.cause is ContainmentViolationException) {
                throw expected.cause as ContainmentViolationException
            }
            throw expected
        }
    }
}
