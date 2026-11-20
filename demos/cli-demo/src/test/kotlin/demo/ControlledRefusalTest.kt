package demo

import io.mazewall.enforcer.api.ContainmentViolationEvidence
import io.mazewall.enforcer.api.ContainmentViolationException
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ControlledRefusalTest {
    @Test
    fun `controlled process permission errors pass without authenticating their provenance`() {
        for (message in listOf("Cannot run program: error=1, Operation not permitted", "posix_spawn failed, error: 13 (Permission denied)")) {
            val failure = inferred(IOException("payload process failed", IOException(message)))
            val result = observe(ControlledOperation.PROCESS_LAUNCH, failure)

            assertEquals(ExpectedResult.PERMISSION_REFUSED, result.expected)
            assertTrue(result.passed, result.render())
            assertSame(failure, assertIs<ScenarioOutcome.PermissionRefused>(result.outcome).cause)
            assertTrue(result.render().contains("INFERRED_PERMISSION_FAILURE"), result.render())
            assertTrue(result.render().contains(message), result.render())
            assertFalse(ScenarioResult("authenticated denial", ExpectedResult.DENIED, result.outcome).passed)
            assertIs<ScenarioOutcome.InfrastructureFailure>(classifyContainmentFailure(failure))
        }
    }

    @Test
    fun `controlled socket permission errors pass with inferred evidence`() {
        for (message in listOf("Operation not permitted", "Permission denied")) {
            val failure = inferred(SocketException(message))
            val result = observe(ControlledOperation.NETWORK_CONNECT, failure)

            assertTrue(result.passed, result.render())
            assertIs<ScenarioOutcome.PermissionRefused>(result.outcome)
            assertTrue(result.render().contains(message), result.render())
        }
    }

    @Test
    fun `successful forbidden operation fails the controlled refusal expectation`() {
        val result = observeControlledRefusal("escaped", ControlledOperation.PROCESS_LAUNCH) { "attack created marker" }

        assertFalse(result.passed)
        assertEquals(2, DemoReport(listOf(result)).exitCode)
        assertIs<ScenarioOutcome.Completed>(result.outcome)
    }

    @Test
    fun `plain permission text cannot satisfy the controlled expectation`() {
        reject(ControlledOperation.PROCESS_LAUNCH, IOException("Cannot run program: error=1, Operation not permitted"))
        reject(ControlledOperation.NETWORK_CONNECT, SocketException("Operation not permitted"))
    }

    @Test
    fun `unknown structured provenance does not become a controlled refusal`() {
        val failure = ContainmentViolationException("unknown", SocketException("Operation not permitted"))

        reject(ControlledOperation.NETWORK_CONNECT, failure)
    }

    @Test
    fun `missing executable connection refusal and connection timeout remain failures`() {
        reject(ControlledOperation.PROCESS_LAUNCH, inferred(IOException("Cannot run program: error=2, No such file or directory")))
        reject(ControlledOperation.NETWORK_CONNECT, inferred(ConnectException("Connection refused")))
        reject(ControlledOperation.NETWORK_CONNECT, inferred(SocketTimeoutException("Connect timed out")))
    }

    @Test
    fun `a permission error for another operation cannot satisfy the expectation`() {
        reject(ControlledOperation.PROCESS_LAUNCH, inferred(SocketException("Operation not permitted")))
        reject(ControlledOperation.NETWORK_CONNECT, inferred(IOException("Cannot run program: error=1, Operation not permitted")))
    }

    @Test
    fun `suppressed cleanup failure prevents a permission refusal from passing`() {
        val failure = inferred(IOException("Cannot run program: error=1, Operation not permitted"))
        failure.addSuppressed(IOException("runtime did not drain"))

        val result = reject(ControlledOperation.PROCESS_LAUNCH, failure)
        assertTrue(result.render().contains("runtime did not drain"), result.render())
    }

    @Test
    fun `cleanup failure on a nested cause also prevents a pass`() {
        val cause = SocketException("Operation not permitted")
        cause.addSuppressed(IOException("worker retirement failed"))

        reject(ControlledOperation.NETWORK_CONNECT, inferred(cause))
    }

    @Test
    fun `lifecycle and completion failures cannot borrow a nested permission denial`() {
        val failures = listOf(
            TimeoutException("execution end timed out").apply { addSuppressed(inferred(SocketException("Operation not permitted"))) },
            InterruptedException("wait interrupted").apply { initCause(inferred(SocketException("Operation not permitted"))) },
            CancellationException("cancelled").apply { initCause(inferred(SocketException("Operation not permitted"))) },
            ExecutionException(inferred(SocketException("Operation not permitted"))),
            CompletionException(inferred(SocketException("Operation not permitted"))),
            inferred(IOException("permission operation failed", TimeoutException("execution end timed out"))),
            inferred(IOException("permission operation failed", InterruptedException("wait interrupted"))),
        )
        try {
            for (failure in failures) reject(ControlledOperation.NETWORK_CONNECT, failure)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `a cause cycle is rejected without looping`() {
        val first = IOException("Cannot run program: error=1, Operation not permitted")
        val second = IOException("cyclic fixture", first)
        first.initCause(second)

        reject(ControlledOperation.PROCESS_LAUNCH, inferred(first))
    }

    @Test
    fun `authenticated denial remains a distinct successful outcome`() {
        val failure = ContainmentViolationException(
            "observed denial",
            errno = 1,
            evidence = ContainmentViolationEvidence.OBSERVED_POLICY_DENIAL,
        )
        val result = observe(ControlledOperation.PROCESS_LAUNCH, failure)

        assertTrue(result.passed, result.render())
        assertIs<ScenarioOutcome.Denied>(result.outcome)
    }

    private fun inferred(cause: Throwable) = ContainmentViolationException(
        "Task reported a containment policy violation",
        cause,
        evidence = ContainmentViolationEvidence.INFERRED_PERMISSION_FAILURE,
    )

    private fun observe(operation: ControlledOperation, failure: Throwable): ScenarioResult =
        observeControlledRefusal("controlled operation", operation) { throw failure }

    private fun reject(operation: ControlledOperation, failure: Throwable): ScenarioResult {
        val result = observe(operation, failure)
        assertFalse(result.passed, result.render())
        assertSame(failure, assertIs<ScenarioOutcome.InfrastructureFailure>(result.outcome).cause)
        assertEquals(2, DemoReport(listOf(result)).exitCode)
        return result
    }
}
