package demo

import io.mazewall.Policy
import io.mazewall.enforcer.ContainedExecutors
import io.mazewall.enforcer.ContainmentViolationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

object SafeRunner {
    fun run(payload: String) {
        val executor = Executors.newSingleThreadExecutor()
        val safeExecutor = ContainedExecutors.wrap(executor, Policy.NO_EXEC)

        try {
            val future =
                safeExecutor.submit<String> {
                    VulnerableLogger.log(payload)
                }
            future.get()
        } catch (expected: ExecutionException) {
            // We intentionally unwrap the ExecutionException to expose the underlying
            // ContainmentViolationException to the caller for clearer demo output.
            if (expected.cause is ContainmentViolationException) {
                throw expected.cause as ContainmentViolationException
            }
            throw expected
        } finally {
            safeExecutor.shutdown()
            executor.shutdown()
        }
    }
}
